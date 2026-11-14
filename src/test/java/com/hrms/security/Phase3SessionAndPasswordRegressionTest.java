package com.hrms.security;

import com.hrms.audit.entity.AuditLog;
import com.hrms.audit.repository.AuditLogRepository;
import com.hrms.department.entity.Department;
import com.hrms.department.entity.JobRole;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.department.repository.JobRoleRepository;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.security.entity.Role;
import com.hrms.security.entity.SessionEndedReason;
import com.hrms.security.entity.User;
import com.hrms.security.entity.UserSession;
import com.hrms.security.repository.UserRepository;
import com.hrms.security.repository.UserSessionRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.session.MapSession;
import org.springframework.session.events.SessionExpiredEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 3 Regression Tests covering:
 * - GAP-01: SessionExpiredEvent tracking, scheduled reconciliation, and absolute timeout enforcement
 * - GAP-03: Removal of dead /password/* routes & authenticated /account/password rotation flow
 */
@SpringBootTest
class Phase3SessionAndPasswordRegressionTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private Filter springSessionRepositoryFilter;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private SessionManagementService sessionManagementService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private JobRoleRepository jobRoleRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(springSessionRepositoryFilter)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("GAP-01: SessionExpiredEvent marks open user_sessions EXPIRED without overwriting LOGOUT")
    void sessionExpiredEventMarksOpenSessionExpiredWithoutOverwritingLogout() {
        User empUser = userRepository.findByEmail("emp1@company.com").orElseThrow();
        String openSessionId = "expired-evt-" + UUID.randomUUID();
        String loggedOutSessionId = "logout-evt-" + UUID.randomUUID();

        UserSession openUs = new UserSession();
        openUs.setSessionId(openSessionId);
        openUs.setUser(empUser);
        openUs.setIpAddress("127.0.0.1");
        openUs.setUserAgent("JUnit");
        openUs.setLoginAt(Instant.now().minusSeconds(3600));
        openUs = userSessionRepository.saveAndFlush(openUs);

        UserSession loggedOutUs = new UserSession();
        loggedOutUs.setSessionId(loggedOutSessionId);
        loggedOutUs.setUser(empUser);
        loggedOutUs.setIpAddress("127.0.0.1");
        loggedOutUs.setUserAgent("JUnit");
        loggedOutUs.setLoginAt(Instant.now().minusSeconds(7200));
        loggedOutUs.setLogoutAt(Instant.now().minusSeconds(3600));
        loggedOutUs.setEndedReason(SessionEndedReason.LOGOUT);
        loggedOutUs = userSessionRepository.saveAndFlush(loggedOutUs);

        try {
            eventPublisher.publishEvent(new SessionExpiredEvent(this, new MapSession(openSessionId)));
            eventPublisher.publishEvent(new SessionExpiredEvent(this, new MapSession(loggedOutSessionId)));

            UserSession reloadedOpen = userSessionRepository.findById(openUs.getId()).orElseThrow();
            assertNotNull(reloadedOpen.getLogoutAt());
            assertEquals(SessionEndedReason.EXPIRED, reloadedOpen.getEndedReason());

            UserSession reloadedLogout = userSessionRepository.findById(loggedOutUs.getId()).orElseThrow();
            assertEquals(SessionEndedReason.LOGOUT, reloadedLogout.getEndedReason());
        } finally {
            userSessionRepository.deleteById(openUs.getId());
            userSessionRepository.deleteById(loggedOutUs.getId());
        }
    }

    @Test
    @DisplayName("GAP-01: Scheduled reconcileExpiredSessions marks open sessions EXPIRED when SPRING_SESSION EXPIRY_TIME elapses")
    void reconcileExpiredSessionsClosesExpiredSpringSessionRows() {
        User empUser = userRepository.findByEmail("emp1@company.com").orElseThrow();
        String primaryId = UUID.randomUUID().toString();
        String expiredSessionId = UUID.randomUUID().toString();
        long nowMillis = Instant.now().toEpochMilli();

        jdbcTemplate.update(
                "INSERT INTO SPRING_SESSION (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME) VALUES (?, ?, ?, ?, ?, ?, ?)",
                primaryId, expiredSessionId, nowMillis - 3_600_000L, nowMillis - 2_000_000L, 1800, nowMillis - 10_000L, "emp1@company.com");

        UserSession openUs = new UserSession();
        openUs.setSessionId(expiredSessionId);
        openUs.setUser(empUser);
        openUs.setIpAddress("127.0.0.1");
        openUs.setUserAgent("JUnit-Reconcile");
        openUs.setLoginAt(Instant.now().minusSeconds(3600));
        openUs = userSessionRepository.saveAndFlush(openUs);

        try {
            int reconciled = sessionManagementService.reconcileExpiredSessions();
            assertTrue(reconciled >= 1);

            UserSession closed = userSessionRepository.findById(openUs.getId()).orElseThrow();
            assertNotNull(closed.getLogoutAt());
            assertEquals(SessionEndedReason.EXPIRED, closed.getEndedReason());
        } finally {
            jdbcTemplate.update("DELETE FROM SPRING_SESSION WHERE PRIMARY_ID = ?", primaryId);
            userSessionRepository.deleteById(openUs.getId());
        }
    }

    @Test
    @DisplayName("GAP-01: AbsoluteSessionTimeoutFilter invalidates expired session, records EXPIRED, and redirects to /login?expired")
    void absoluteTimeoutFilterEnforcesMaxLifetimeOnRealSpringSession() throws Exception {
        String email = "temp.abs." + UUID.randomUUID().toString().substring(0, 8) + "@company.com";
        String rawPass = "TempPass#1234";

        User tempUser = new User();
        tempUser.setEmail(email);
        tempUser.setPasswordHash(passwordEncoder.encode(rawPass));
        tempUser.setRole(Role.EMPLOYEE);
        tempUser.setActive(true);
        tempUser = userRepository.save(tempUser);

        Department dept = departmentRepository.findAll().get(0);
        JobRole jobRole = jobRoleRepository.findByDepartmentId(dept.getId()).get(0);

        Employee tempEmp = new Employee();
        tempEmp.setEmployeeCode("EMP-ABS-" + UUID.randomUUID().toString().substring(0, 5));
        tempEmp.setFullName("Temp Absolute Timeout User");
        tempEmp.setDepartment(dept);
        tempEmp.setJobRole(jobRole);
        tempEmp.setUser(tempUser);
        tempEmp.setDateOfJoining(LocalDate.of(2026, 1, 1));
        tempEmp.setStatus(EmployeeStatus.ACTIVE);
        tempEmp = employeeRepository.save(tempEmp);

        try {
            MvcResult loginResult = mockMvc.perform(post("/login")
                            .with(csrf())
                            .param("username", email)
                            .param("password", rawPass))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/dashboard"))
                    .andReturn();

            Cookie sessionCookie = loginResult.getResponse().getCookie("SESSION");
            assertNotNull(sessionCookie);

            mockMvc.perform(get("/dashboard").cookie(sessionCookie))
                    .andExpect(status().isOk());

            // Backdate CREATION_TIME by 9 hours (> default 8h absolute-timeout-hours), keep LAST_ACCESS_TIME fresh
            long nineHoursAgoMillis = Instant.now().minusSeconds(9 * 3600L).toEpochMilli();
            int updatedRows = jdbcTemplate.update(
                    "UPDATE SPRING_SESSION SET CREATION_TIME = ? WHERE LOWER(PRINCIPAL_NAME) = LOWER(?)",
                    nineHoursAgoMillis, email);
            assertEquals(1, updatedRows);

            // Next request must be invalidated and redirected to /login?expired
            mockMvc.perform(get("/dashboard").cookie(sessionCookie))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?expired"));

            List<UserSession> sessions = userSessionRepository.findByUserIdOrderByLoginAtDesc(tempUser.getId());
            assertFalse(sessions.isEmpty());
            assertTrue(sessions.stream().allMatch(us ->
                    us.getLogoutAt() != null && us.getEndedReason() == SessionEndedReason.EXPIRED));

            Integer remainingSpringSessions = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM SPRING_SESSION WHERE LOWER(PRINCIPAL_NAME) = LOWER(?)",
                    Integer.class, email);
            assertEquals(0, remainingSpringSessions);

            // Render /login?expired banner cleanly
            mockMvc.perform(get("/login").param("expired", ""))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Your session expired. Please sign in again.")));
        } finally {
            jdbcTemplate.update("DELETE FROM SPRING_SESSION WHERE LOWER(PRINCIPAL_NAME) = LOWER(?)", email);
            List<UserSession> sessions = userSessionRepository.findByUserIdOrderByLoginAtDesc(tempUser.getId());
            if (!sessions.isEmpty()) {
                userSessionRepository.deleteAll(sessions);
            }
            employeeRepository.findById(tempEmp.getId()).ifPresent(employeeRepository::delete);
            userRepository.findById(tempUser.getId()).ifPresent(userRepository::delete);
        }
    }

    @Test
    @DisplayName("GAP-01: AbsoluteSessionTimeoutFilter also returns 401 for expired /api/** requests")
    void absoluteTimeoutFilterReturns401ForApiRequests() throws Exception {
        Clock fixedNow = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);
        AbsoluteSessionTimeoutFilter filter = new AbsoluteSessionTimeoutFilter(sessionManagementService, fixedNow, 8);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/analytics/headcount-by-dept");
        MockHttpSession session = new MockHttpSession() {
            @Override
            public long getCreationTime() {
                return Instant.parse("2026-11-12T01:00:00Z").toEpochMilli(); // 9 hours earlier
            }
        };
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        assertTrue(session.isInvalid());
    }

    @Test
    @DisplayName("GAP-03: Dead public routes /password/forgot and /password/reset are removed from permitAll and /account/password requires authentication")
    void deadPasswordRoutesBlockedAndAccountPasswordProtected() throws Exception {
        mockMvc.perform(get("/password/forgot"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/password/reset"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/account/password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("GAP-03: Authenticated user can rotate password, updates password_reset_at, logs safe audit entry, and validates inputs")
    @WithUserDetails("emp1@company.com")
    void authenticatedPasswordRotationUpdatesHashTimestampAndAuditSafely() throws Exception {
        User empUser = userRepository.findByEmail("emp1@company.com").orElseThrow();
        String originalHash = empUser.getPasswordHash();
        Instant originalResetAt = empUser.getPasswordResetAt();
        String tempCurrent = "TemporaryCurrent#2026";
        String rotatedPassword = "RotatedStrongPass#987";

        try {
            // Prepare known current password hash safely before restoring in finally
            empUser.setPasswordHash(passwordEncoder.encode(tempCurrent));
            empUser = userRepository.saveAndFlush(empUser);

            // Page renders cleanly
            mockMvc.perform(get("/account/password"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Change Account Password")));

            // Wrong current password rejected
            mockMvc.perform(post("/account/password")
                            .with(csrf())
                            .param("currentPassword", "WrongPass#111")
                            .param("newPassword", rotatedPassword)
                            .param("confirmPassword", rotatedPassword))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Current password is incorrect.")));

            // Mismatched confirm password rejected
            mockMvc.perform(post("/account/password")
                            .with(csrf())
                            .param("currentPassword", tempCurrent)
                            .param("newPassword", rotatedPassword)
                            .param("confirmPassword", "DifferentPass#987"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("New password and confirmation do not match.")));

            // Valid change succeeds and redirects
            mockMvc.perform(post("/account/password")
                            .with(csrf())
                            .param("currentPassword", tempCurrent)
                            .param("newPassword", rotatedPassword)
                            .param("confirmPassword", rotatedPassword))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/account/password"));

            User updated = userRepository.findById(empUser.getId()).orElseThrow();
            assertTrue(passwordEncoder.matches(rotatedPassword, updated.getPasswordHash()));
            assertNotNull(updated.getPasswordResetAt());

            List<AuditLog> audits = auditLogRepository.findByUserIdWithUser(
                    empUser.getId(), org.springframework.data.domain.PageRequest.of(0, 20));
            AuditLog latestPwdAudit = audits.stream()
                    .filter(a -> "PASSWORD_CHANGED".equals(a.getAction()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected PASSWORD_CHANGED audit entry"));
            assertNotNull(latestPwdAudit.getNewData());
            assertEquals(Boolean.TRUE, latestPwdAudit.getNewData().get("passwordChanged"));
            assertFalse(latestPwdAudit.getNewData().toString().contains(rotatedPassword),
                    "Audit JSONB must never store plaintext password");
            assertFalse(latestPwdAudit.getNewData().toString().contains(updated.getPasswordHash()),
                    "Audit JSONB must never store password hash");
        } finally {
            User restore = userRepository.findById(empUser.getId()).orElseThrow();
            restore.setPasswordHash(originalHash);
            restore.setPasswordResetAt(originalResetAt);
            userRepository.saveAndFlush(restore);
        }
    }
}
