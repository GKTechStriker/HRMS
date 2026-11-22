package com.hrms.security;

import com.hrms.audit.entity.AuditLog;
import com.hrms.audit.repository.AuditLogRepository;
import com.hrms.security.entity.User;
import com.hrms.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Brute-force mitigation and account lockout service.
 * Tracks consecutive failed login attempts and temporarily locks the account for 15 minutes
 * after 5 failed attempts, recording immutable audit records for SOC-2/compliance.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public LoginAttemptService(UserRepository userRepository,
                               AuditLogRepository auditLogRepository,
                               Clock clock) {
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    @Transactional
    public void recordLoginSuccess(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email.trim());
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
                user.setFailedLoginAttempts(0);
                user.setLockedUntil(null);
                userRepository.save(user);
                log.info("Reset failed login counter for user '{}'", user.getEmail());
            }
        }
    }

    @Transactional
    public void recordLoginFailure(String email, String ipAddress) {
        if (email == null || email.isBlank()) {
            return;
        }
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email.trim());
        if (userOpt.isEmpty()) {
            log.warn("Login failure for non-existent user identifier: '{}' from IP: '{}'", email, ipAddress);
            return;
        }

        User user = userOpt.get();
        Instant now = clock.instant();

        // If previously locked and lock duration has passed, reset counter to 1
        if (user.getLockedUntil() != null && !user.getLockedUntil().isAfter(now)) {
            user.setFailedLoginAttempts(1);
            user.setLockedUntil(null);
        } else {
            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
        }

        if (user.getFailedLoginAttempts() >= MAX_FAILED_ATTEMPTS && (user.getLockedUntil() == null || !user.getLockedUntil().isAfter(now))) {
            user.setLockedUntil(now.plus(LOCK_DURATION));
            log.warn("ACCOUNT LOCKED: User '{}' exceeded max failed login attempts ({}/{}). Locked until '{}'. IP: '{}'",
                    user.getEmail(), user.getFailedLoginAttempts(), MAX_FAILED_ATTEMPTS, user.getLockedUntil(), ipAddress);

            // Record security audit log
            AuditLog audit = new AuditLog();
            audit.setUser(user);
            audit.setHrCode(user.getHrCode());
            audit.setSessionId("SYSTEM");
            audit.setIpAddress(ipAddress != null ? ipAddress : "127.0.0.1");
            audit.setAction("ACCOUNT_LOCKED_BRUTE_FORCE");
            audit.setTargetType("User");
            audit.setTargetId(user.getId());
            audit.setOccurredAt(now);
            audit.setNewData(Map.of(
                    "email", user.getEmail(),
                    "failedAttempts", user.getFailedLoginAttempts(),
                    "lockedUntil", user.getLockedUntil().toString(),
                    "ipAddress", ipAddress != null ? ipAddress : "UNKNOWN"
            ));
            auditLogRepository.save(audit);
        } else {
            log.warn("Failed login attempt ({}/{}) for user '{}' from IP: '{}'",
                    user.getFailedLoginAttempts(), MAX_FAILED_ATTEMPTS, user.getEmail(), ipAddress);
        }

        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public boolean isAccountLocked(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return userRepository.findByEmailIgnoreCase(email.trim())
                .map(u -> u.getLockedUntil() != null && u.getLockedUntil().isAfter(clock.instant()))
                .orElse(false);
    }

    @Transactional
    public void unlockUser(UUID userId, User adminUser) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + userId));

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        log.info("User '{}' was administratively unlocked by admin '{}'", user.getEmail(), adminUser != null ? adminUser.getEmail() : "SYSTEM");

        AuditLog audit = new AuditLog();
        audit.setUser(adminUser != null ? adminUser : user);
        audit.setHrCode(adminUser != null ? adminUser.getHrCode() : user.getHrCode());
        audit.setSessionId("SYSTEM");
        audit.setIpAddress("127.0.0.1");
        audit.setAction("ACCOUNT_UNLOCKED_BY_ADMIN");
        audit.setTargetType("User");
        audit.setTargetId(user.getId());
        audit.setOccurredAt(clock.instant());
        audit.setNewData(Map.of(
                "unlockedUserId", user.getId().toString(),
                "unlockedUserEmail", user.getEmail(),
                "unlockedBy", adminUser != null ? adminUser.getEmail() : "SYSTEM"
        ));
        auditLogRepository.save(audit);
    }
}
