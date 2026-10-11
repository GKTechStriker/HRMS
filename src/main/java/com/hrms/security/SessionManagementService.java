package com.hrms.security;

import com.hrms.audit.AuditContext;
import com.hrms.audit.AuditAspect;
import com.hrms.audit.Audited;
import com.hrms.exception.BusinessException;
import com.hrms.exception.ResourceNotFoundException;
import com.hrms.security.entity.Role;
import com.hrms.security.entity.SessionEndedReason;
import com.hrms.security.entity.User;
import com.hrms.security.entity.UserSession;
import com.hrms.security.repository.UserRepository;
import com.hrms.security.repository.UserSessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages companion session tracking (user_sessions) and forced session revocation
 * against Spring Session JDBC (Section 4.1, 6.1, & 7.1).
 */
@Service
public class SessionManagementService {

    private static final Logger log = LoggerFactory.getLogger(SessionManagementService.class);

    private final UserSessionRepository userSessionRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public SessionManagementService(UserSessionRepository userSessionRepository,
                                    UserRepository userRepository,
                                    JdbcTemplate jdbcTemplate) {
        this.userSessionRepository = userSessionRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void recordLoginSession(CustomUserDetails principal, HttpServletRequest request) {
        HttpSession httpSession = request.getSession(false);
        if (httpSession == null) {
            return;
        }
        String sessionId = httpSession.getId();
        Optional<UserSession> existing = userSessionRepository.findBySessionId(sessionId);
        if (existing.isPresent()) {
            return;
        }

        User user = userRepository.getReferenceById(principal.getUserId());
        String ipAddress = AuditAspect.extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = "Unknown";
        }

        UserSession userSession = new UserSession();
        userSession.setSessionId(sessionId);
        userSession.setUser(user);
        userSession.setIpAddress(ipAddress);
        userSession.setUserAgent(userAgent);
        userSession.setLoginAt(Instant.now());

        userSessionRepository.save(userSession);
        log.info("Session started: user={}, role={}, sessionId={}, ip={}",
                principal.getEmail(), principal.getRole(), sessionId, ipAddress);
    }

    @Transactional
    public void recordLogoutSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        userSessionRepository.findBySessionId(sessionId).ifPresent(userSession -> {
            if (userSession.getLogoutAt() == null) {
                userSession.setLogoutAt(Instant.now());
                userSession.setEndedReason(SessionEndedReason.LOGOUT);
                userSessionRepository.save(userSession);
                log.info("Session logged out: sessionId={}", sessionId);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<UserSession> getSessionsForPrincipal(CustomUserDetails principal) {
        if (principal.getRole() == Role.SUPER_ADMIN) {
            return userSessionRepository.findAllOrderByLoginAtDesc();
        }
        return userSessionRepository.findByUserIdOrderByLoginAtDesc(principal.getUserId());
    }

    /**
     * Revokes an active session (Section 4.1 Scope Exception & Section 6.1):
     * - SUPER_ADMIN can revoke ANY session
     * - HR_ADMIN and EMPLOYEE can revoke ONLY their own sessions
     * Every revocation is recorded in audit_log via @Audited.
     */
    @Transactional
    @Audited(action = "SESSION_REVOKED", targetType = "UserSession")
    public void revokeSession(UUID userSessionId, CustomUserDetails revoker) {
        UserSession targetSession = userSessionRepository.findById(userSessionId)
                .orElseThrow(() -> ResourceNotFoundException.forEntity("UserSession", userSessionId.toString()));

        boolean isSuperAdmin = revoker.getRole() == Role.SUPER_ADMIN;
        boolean isOwnSession = targetSession.getUser().getId().equals(revoker.getUserId());

        if (!isSuperAdmin && !isOwnSession) {
            throw new AccessDeniedException("You are only permitted to revoke your own sessions.");
        }

        if (targetSession.getLogoutAt() != null) {
            throw new BusinessException("Session is already terminated.");
        }

        Instant now = Instant.now();
        User revokerUser = userRepository.getReferenceById(revoker.getUserId());

        Map<String, Object> previousState = Map.of(
                "sessionId", targetSession.getSessionId(),
                "ownerEmail", targetSession.getUser().getEmail(),
                "status", "ACTIVE"
        );

        targetSession.setLogoutAt(now);
        targetSession.setEndedReason(SessionEndedReason.REVOKED);
        targetSession.setRevokedBy(revokerUser);
        userSessionRepository.save(targetSession);

        // Immediately delete the session from Spring Session JDBC store so the next request is rejected
        int deletedRows = jdbcTemplate.update(
                "DELETE FROM SPRING_SESSION WHERE SESSION_ID = ?",
                targetSession.getSessionId()
        );

        Map<String, Object> newState = Map.of(
                "sessionId", targetSession.getSessionId(),
                "ownerEmail", targetSession.getUser().getEmail(),
                "status", "REVOKED",
                "endedReason", SessionEndedReason.REVOKED.name(),
                "revokedByEmail", revoker.getEmail(),
                "springSessionDeleted", deletedRows > 0
        );

        AuditContext.setTarget(targetSession.getId(), previousState, newState);
        log.info("Session revoked: targetSessionId={}, owner={}, revokedBy={}, springSessionRowsDeleted={}",
                targetSession.getSessionId(), targetSession.getUser().getEmail(), revoker.getEmail(), deletedRows);
    }
}
