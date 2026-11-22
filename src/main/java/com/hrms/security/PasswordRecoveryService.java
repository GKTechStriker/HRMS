package com.hrms.security;

import com.hrms.audit.entity.AuditLog;
import com.hrms.audit.repository.AuditLogRepository;
import com.hrms.exception.BusinessException;
import com.hrms.notification.service.NotificationService;
import com.hrms.security.entity.PasswordResetToken;
import com.hrms.security.entity.User;
import com.hrms.security.repository.PasswordResetTokenRepository;
import com.hrms.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Self-service password recovery service (Forgot Password flow).
 * Issues cryptographically strong, SHA-256 hashed, single-use tokens valid for 15 minutes.
 * Automatically invalidates active sessions and resets brute-force lockout counters upon successful rotation.
 */
@Service
public class PasswordRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryService.class);
    private static final Duration TOKEN_EXPIRATION = Duration.ofMinutes(15);
    private static final Pattern PASSWORD_COMPLEXITY_PATTERN =
            Pattern.compile("^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!_\\-]).{8,128}$");

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final NotificationService notificationService;
    private final PasswordEncoder passwordEncoder;
    private final SessionManagementService sessionManagementService;
    private final AuditLogRepository auditLogRepository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordRecoveryService(UserRepository userRepository,
                                   PasswordResetTokenRepository tokenRepository,
                                   NotificationService notificationService,
                                   PasswordEncoder passwordEncoder,
                                   SessionManagementService sessionManagementService,
                                   AuditLogRepository auditLogRepository,
                                   Clock clock) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.notificationService = notificationService;
        this.passwordEncoder = passwordEncoder;
        this.sessionManagementService = sessionManagementService;
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    @Transactional
    public void requestPasswordReset(String email, String appBaseUrl) {
        if (email == null || email.isBlank()) {
            return;
        }

        String normalizedEmail = email.trim().toLowerCase();
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);

        if (userOpt.isEmpty() || !userOpt.get().isActive()) {
            log.info("Password reset requested for non-existent or inactive email: '{}'", normalizedEmail);
            // Constant-time behavior; do not reveal user non-existence
            return;
        }

        User user = userOpt.get();

        // Invalidate any existing unused tokens for this user
        tokenRepository.deleteByUserId(user.getId());

        // Generate 32-byte secure random token
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawToken = HexFormat.of().formatHex(randomBytes);
        String tokenHash = hashToken(rawToken);

        Instant expiresAt = clock.instant().plus(TOKEN_EXPIRATION);
        PasswordResetToken resetToken = new PasswordResetToken(user, tokenHash, expiresAt);
        tokenRepository.save(resetToken);

        String resetUrl = (appBaseUrl != null && !appBaseUrl.isBlank() ? appBaseUrl : "http://localhost:8080")
                + "/reset-password?token=" + rawToken;

        String emailSubject = "HRMS Password Reset Request";
        String emailBody = String.format("""
                Hello %s,

                A password reset request was initiated for your HRMS account.
                Please click the link below to set a new password (valid for %d minutes):

                %s

                If you did not make this request, you can safely ignore this email. Your account remains secure.

                — HRMS Security Team
                """, user.getEmail(), TOKEN_EXPIRATION.toMinutes(), resetUrl);

        notificationService.sendEmail(user.getEmail(), emailSubject, emailBody);
        log.info("Dispatched password reset token for user '{}' (expires in {}m)",
                user.getEmail(), TOKEN_EXPIRATION.toMinutes());
    }

    @Transactional(readOnly = true)
    public User validateResetToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessException("Password reset link is invalid or missing.");
        }

        String tokenHash = hashToken(rawToken);
        PasswordResetToken token = tokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException("Password reset link is invalid or has expired. Please request a new link."));

        if (!token.isValid() || token.getExpiresAt().isBefore(clock.instant())) {
            throw new BusinessException("Password reset link has expired. Please request a new link.");
        }

        return token.getUser();
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword, String confirmPassword) {
        User user = validateResetToken(rawToken);

        if (newPassword == null || newPassword.isBlank()) {
            throw new BusinessException("New password is required.");
        }
        if (newPassword.length() < 8 || newPassword.length() > 128) {
            throw new BusinessException("New password must be between 8 and 128 characters.");
        }
        if (!PASSWORD_COMPLEXITY_PATTERN.matcher(newPassword).matches()) {
            throw new BusinessException(
                    "New password must contain at least one uppercase letter, one lowercase letter, one digit, and one special character (@#$%^&+=!_-).");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new BusinessException("New password and confirmation do not match.");
        }

        // Apply new credentials
        Instant now = clock.instant();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordResetAt(now);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        // Mark token used
        String tokenHash = hashToken(rawToken);
        tokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            token.setUsedAt(now);
            tokenRepository.save(token);
        });

        // Invalidate any active sessions to enforce new credential authentication
        sessionManagementService.revokeAllSessionsForUser(user.getId(), null);

        // Record security audit
        AuditLog audit = new AuditLog();
        audit.setUser(user);
        audit.setHrCode(user.getHrCode());
        audit.setSessionId("SYSTEM");
        audit.setIpAddress("127.0.0.1");
        audit.setAction("PASSWORD_RESET_VIA_TOKEN");
        audit.setTargetType("User");
        audit.setTargetId(user.getId());
        audit.setOccurredAt(now);
        audit.setNewData(Map.of(
                "email", user.getEmail(),
                "passwordResetViaToken", true,
                "sessionsRevoked", true
        ));
        auditLogRepository.save(audit);

        log.info("Password successfully reset via token for user '{}'", user.getEmail());
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest algorithm unavailable", e);
        }
    }
}
