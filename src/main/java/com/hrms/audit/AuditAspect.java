package com.hrms.audit;

import com.hrms.audit.entity.AuditLog;
import com.hrms.audit.repository.AuditLogRepository;
import com.hrms.security.CustomUserDetails;
import com.hrms.security.entity.User;
import com.hrms.security.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-cutting AOP Aspect that intercepts @Audited service methods and writes an
 * immutable AuditLog record in the same transaction (Section 7.2).
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AuditAspect(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    @Around("@annotation(audited)")
    public Object aroundAuditedMethod(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        try {
            Object result = joinPoint.proceed();
            recordAuditEntry(joinPoint, audited, result);
            return result;
        } finally {
            AuditContext.clear();
        }
    }

    private void recordAuditEntry(ProceedingJoinPoint joinPoint, Audited audited, Object result) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails principal)) {
            log.warn("Audited action '{}' executed without CustomUserDetails principal; skipping audit write.",
                    audited.action());
            return;
        }

        User actor = userRepository.getReferenceById(principal.getUserId());

        String sessionId = "SYSTEM";
        String ipAddress = "127.0.0.1";
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            HttpSession session = request.getSession(false);
            if (session != null) {
                sessionId = session.getId();
            }
            ipAddress = extractClientIp(request);
        }

        AuditContext.Snapshot snapshot = AuditContext.get();
        UUID targetId = snapshot != null && snapshot.targetId() != null
                ? snapshot.targetId()
                : resolveTargetId(result, joinPoint.getArgs());

        Map<String, Object> previousData = snapshot != null ? snapshot.previousData() : null;
        Map<String, Object> newData = snapshot != null ? snapshot.newData() : null;
        if (newData == null && result != null) {
            newData = Map.of("resultSummary", result.toString());
        }

        AuditLog entry = new AuditLog();
        entry.setSessionId(sessionId);
        entry.setUser(actor);
        entry.setHrCode(principal.getHrCode());
        entry.setAction(audited.action());
        entry.setTargetType(audited.targetType());
        entry.setTargetId(targetId != null ? targetId : principal.getUserId());
        entry.setPreviousData(previousData);
        entry.setNewData(newData);
        entry.setIpAddress(ipAddress);
        entry.setOccurredAt(Instant.now());

        auditLogRepository.save(entry);
        log.info("Audit recorded: action={}, actor={}, hrCode={}, session={}, targetType={}, targetId={}",
                entry.getAction(), principal.getEmail(), entry.getHrCode(), sessionId,
                entry.getTargetType(), entry.getTargetId());
    }

    private UUID resolveTargetId(Object result, Object[] args) {
        if (result instanceof UUID uuid) {
            return uuid;
        }
        if (result != null) {
            try {
                Method getId = result.getClass().getMethod("getId");
                Object val = getId.invoke(result);
                if (val instanceof UUID uuid) {
                    return uuid;
                }
            } catch (Exception ignored) {
            }
        }
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof UUID uuid) {
                    return uuid;
                }
            }
        }
        return UUID.randomUUID();
    }

    public static String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }
}
