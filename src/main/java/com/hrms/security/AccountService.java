package com.hrms.security;

import com.hrms.audit.AuditContext;
import com.hrms.audit.Audited;
import com.hrms.exception.BusinessException;
import com.hrms.exception.ResourceNotFoundException;
import com.hrms.security.entity.User;
import com.hrms.security.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Service handling authenticated account security operations such as
 * self-service password rotation with password_reset_at tracking and audit logging (GAP-03).
 */
@Service
public class AccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AccountService(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public User getUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.forEntity("User", userId.toString()));
    }

    @Transactional
    @Audited(action = "PASSWORD_CHANGED", targetType = "User")
    public void changePassword(UUID userId, String currentPassword, String newPassword, String confirmPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.forEntity("User", userId.toString()));

        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException("Current password is incorrect.");
        }
        if (newPassword == null || newPassword.isBlank() || newPassword.length() < 8) {
            throw new BusinessException("New password must be at least 8 characters long.");
        }
        if (newPassword.length() > 128) {
            throw new BusinessException("New password must not exceed 128 characters.");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new BusinessException("New password and confirmation do not match.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException("New password must be different from your current password.");
        }

        Instant previousResetAt = user.getPasswordResetAt();
        Instant now = Instant.now(clock);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordResetAt(now);
        userRepository.save(user);

        AuditContext.setTarget(
                user.getId(),
                Map.of(
                        "email", user.getEmail(),
                        "previousPasswordResetAt", previousResetAt != null ? previousResetAt.toString() : "NEVER"
                ),
                Map.of(
                        "email", user.getEmail(),
                        "passwordResetAt", now.toString(),
                        "passwordChanged", true
                )
        );
    }
}
