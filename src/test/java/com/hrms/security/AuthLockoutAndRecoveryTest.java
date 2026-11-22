package com.hrms.security;

import com.hrms.audit.repository.AuditLogRepository;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.security.entity.PasswordResetToken;
import com.hrms.security.entity.Role;
import com.hrms.security.entity.User;
import com.hrms.security.repository.PasswordResetTokenRepository;
import com.hrms.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verification test suite for Account Lockout, Brute-Force Defense,
 * and Self-Service Password Recovery (Forgot Password Flow).
 */
@SpringBootTest
class AuthLockoutAndRecoveryTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private PasswordRecoveryService passwordRecoveryService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("Brute-Force Defense: 5 consecutive failed logins temporarily lock account for 15 minutes")
    void failedLoginsTriggerAccountLockout() throws Exception {
        String testEmail = "lockout_test_" + UUID.randomUUID() + "@company.com";
        String correctPassword = "CorrectPass#2026";

        User user = new User();
        user.setEmail(testEmail);
        user.setPasswordHash(passwordEncoder.encode(correctPassword));
        user.setRole(Role.EMPLOYEE);
        user.setActive(true);
        user.setPasswordResetAt(Instant.now());
        user = userRepository.saveAndFlush(user);

        // First 4 failed attempts redirect to /login?error
            for (int i = 1; i <= 4; i++) {
                mockMvc.perform(post("/login")
                                .with(csrf())
                                .param("username", testEmail)
                                .param("password", "WrongPassword#1"))
                        .andExpect(status().is3xxRedirection())
                        .andExpect(redirectedUrl("/login?error"));

                User reloaded = userRepository.findById(user.getId()).orElseThrow();
                assertEquals(i, reloaded.getFailedLoginAttempts(), "Failed attempt counter must increment");
                assertNull(reloaded.getLockedUntil(), "User must not be locked before 5 attempts");
            }

            // 5th failed attempt triggers lockout and redirects to /login?locked
            mockMvc.perform(post("/login")
                            .with(csrf())
                            .param("username", testEmail)
                            .param("password", "WrongPassword#1"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?locked"));

            User lockedUser = userRepository.findById(user.getId()).orElseThrow();
            assertEquals(5, lockedUser.getFailedLoginAttempts());
            assertNotNull(lockedUser.getLockedUntil());
            assertTrue(lockedUser.isAccountLocked(), "User must be locked after 5 failed attempts");

            // 6th attempt WITH CORRECT PASSWORD is still blocked because account is locked
            mockMvc.perform(post("/login")
                            .with(csrf())
                            .param("username", testEmail)
                            .param("password", correctPassword))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?locked"));

            // Check that ACCOUNT_LOCKED_BRUTE_FORCE was audited
            assertTrue(auditLogRepository.findAll().stream()
                    .anyMatch(a -> "ACCOUNT_LOCKED_BRUTE_FORCE".equals(a.getAction())
                            && testEmail.equals(a.getNewData().get("email"))),
                    "Lockout event must be recorded in immutable audit log");
    }

    @Test
    @DisplayName("Admin Unlock: HR Admin can unlock an employee account via POST /employees/{id}/unlock")
    @WithUserDetails("hr1@company.com")
    void hrAdminCanUnlockLockedEmployee() throws Exception {
        // Find an active employee
        Employee employee = employeeRepository.findAll().stream()
                .filter(e -> e.getUser() != null)
                .findFirst()
                .orElseThrow();
        UUID empUserId = employee.getUser().getId();
        User empUser = userRepository.findById(empUserId).orElseThrow();

        try {
            // Simulate locked state
            empUser.setFailedLoginAttempts(5);
            empUser.setLockedUntil(Instant.now().plusSeconds(900));
            userRepository.saveAndFlush(empUser);

            // HR Admin posts to /employees/{id}/unlock
            mockMvc.perform(post("/employees/" + employee.getId() + "/unlock").with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/employees/" + employee.getId()))
                    .andExpect(flash().attributeExists("successMessage"));

            User unlocked = userRepository.findById(empUserId).orElseThrow();
            assertEquals(0, unlocked.getFailedLoginAttempts(), "Failed attempts counter must be reset");
            assertNull(unlocked.getLockedUntil(), "lockedUntil must be cleared");
            assertFalse(unlocked.isAccountLocked(), "Account must no longer be locked");
        } finally {
            User restoreUser = userRepository.findById(empUserId).orElse(null);
            if (restoreUser != null) {
                restoreUser.setFailedLoginAttempts(0);
                restoreUser.setLockedUntil(null);
                userRepository.saveAndFlush(restoreUser);
            }
        }
    }

    @Test
    @DisplayName("Password Recovery: End-to-end token generation, validation, and password reset")
    void passwordRecoveryLifecycle() throws Exception {
        String testEmail = "recovery_" + UUID.randomUUID() + "@company.com";
        String initialPassword = "OldPassword#123";
        String newPassword = "NewStrongPassword#987";

        User initialUser = new User();
        initialUser.setEmail(testEmail);
        initialUser.setPasswordHash(passwordEncoder.encode(initialPassword));
        initialUser.setRole(Role.EMPLOYEE);
        initialUser.setActive(true);
        initialUser.setPasswordResetAt(Instant.now());
        initialUser.setFailedLoginAttempts(3); // Some failed attempts
        final User user = userRepository.saveAndFlush(initialUser);

        try {
            // 1. Request password reset
            mockMvc.perform(post("/forgot-password")
                            .with(csrf())
                            .param("email", testEmail))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/forgot-password?sent=true"))
                    .andExpect(flash().attributeExists("infoMessage"));

            // Verify token was stored in DB
            PasswordResetToken token = tokenRepository.findAll().stream()
                    .filter(t -> t.getUser().getId().equals(user.getId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected token in DB"));

            assertTrue(token.isValid());
            assertNull(token.getUsedAt());

            // 2. Validate token on GET /reset-password
            // We can retrieve raw token by generating directly or testing service validation
            passwordRecoveryService.requestPasswordReset(testEmail, "http://localhost:8080");
            PasswordResetToken freshToken = tokenRepository.findAll().stream()
                    .filter(t -> t.getUser().getId().equals(user.getId()))
                    .findFirst()
                    .orElseThrow();

            // 3. Reset password using invalid token -> fails
            mockMvc.perform(post("/reset-password")
                            .with(csrf())
                            .param("token", "fake-token-12345")
                            .param("newPassword", newPassword)
                            .param("confirmPassword", newPassword))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/reset-password?token=fake-token-12345"))
                    .andExpect(flash().attributeExists("errorMessage"));

            // 4. Reset password using service with known raw token
            String rawToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            // Pre-seed a known token
            String tokenHash = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
            PasswordResetToken knownToken = new PasswordResetToken(user, tokenHash, Instant.now().plusSeconds(900));
            tokenRepository.saveAndFlush(knownToken);

            // Valid password reset via POST
            mockMvc.perform(post("/reset-password")
                            .with(csrf())
                            .param("token", rawToken)
                            .param("newPassword", newPassword)
                            .param("confirmPassword", newPassword))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?resetSuccess=true"))
                    .andExpect(flash().attributeExists("successMessage"));

            // Reload user and verify credentials updated
            User updatedUser = userRepository.findById(user.getId()).orElseThrow();
            assertTrue(passwordEncoder.matches(newPassword, updatedUser.getPasswordHash()));
            assertEquals(0, updatedUser.getFailedLoginAttempts(), "Failed login attempts must be reset");
            assertNull(updatedUser.getLockedUntil());

            // Token is now used
            PasswordResetToken usedToken = tokenRepository.findById(knownToken.getId()).orElseThrow();
            assertNotNull(usedToken.getUsedAt(), "Token must be marked used");
            assertFalse(usedToken.isValid(), "Used token must not be valid");

            // Re-attempting reset with same token is rejected
            mockMvc.perform(post("/reset-password")
                            .with(csrf())
                            .param("token", rawToken)
                            .param("newPassword", "AnotherPassword#456")
                            .param("confirmPassword", "AnotherPassword#456"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/reset-password?token=" + rawToken))
                    .andExpect(flash().attributeExists("errorMessage"));

        } finally {
            tokenRepository.deleteByUserId(user.getId());
        }
    }
}
