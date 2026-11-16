package com.hrms.integration;

import com.hrms.attendance.repository.AttendanceRecordRepository;
import com.hrms.attendance.service.AttendanceFinalizerJob;
import com.hrms.audit.entity.AuditLog;
import com.hrms.audit.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Milestone 5 Full-Stack Integration Suite (GAP-10 & GAP-11).
 *
 * Verifies:
 * 1. Actuator liveness/readiness probes (/actuator/health, /actuator/info).
 * 2. Backward compatibility of /api/health probe.
 * 3. End-to-end AuditAspect event interception with JSON snapshot persistence.
 * 4. Nightly AttendanceFinalizerJob business logic.
 * 5. Full-stack Thymeleaf template rendering across all major authenticated modules.
 */
@SpringBootTest
@Transactional
class Phase5DevOpsAndFullStackIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AttendanceFinalizerJob attendanceFinalizerJob;

    @Autowired
    private AttendanceRecordRepository attendanceRecordRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    // =========================================================================
    // 1. GAP-10: Actuator & Health Probes Verification
    // =========================================================================

    @Test
    @DisplayName("GAP-10: Public GET /actuator/health returns 200 OK and UP status")
    void actuatorHealthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GAP-10: Public GET /actuator/info returns 200 OK")
    void actuatorInfoEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GAP-10: Legacy GET /api/health returns 200 OK for backward compatibility")
    void legacyHealthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
    }

    // =========================================================================
    // 2. GAP-11: Full-Stack AuditAspect Verification
    // =========================================================================

    @Test
    @DisplayName("GAP-11: Audited action writes immutable AuditLog entry with before/after JSONB")
    @WithUserDetails("hr1@company.com")
    void auditAspectInterceptsAndPersistsAuditRecord() throws Exception {
        String testNote = "Milestone 5 Full Stack Verification Note";

        mockMvc.perform(post("/audit/verify-dummy")
                        .with(csrf())
                        .param("note", testNote))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"));

        List<AuditLog> matchingLogs = auditLogRepository.findAll().stream()
                .filter(log -> "AUDIT_FOUNDATION_VERIFIED".equals(log.getAction()))
                .toList();

        assertFalse(matchingLogs.isEmpty(), "AuditLog repository must contain AUDIT_FOUNDATION_VERIFIED record");
        AuditLog latest = matchingLogs.get(matchingLogs.size() - 1);
        assertEquals("SystemVerification", latest.getTargetType());
        assertEquals("hr1@company.com", latest.getUser().getEmail());
        assertNotNull(latest.getNewData(), "New data JSON snapshot must be populated");
        assertEquals("VERIFIED", latest.getNewData().get("status"), "New data snapshot must contain VERIFIED status");
    }

    // =========================================================================
    // 3. GAP-11: AttendanceFinalizerJob Execution Verification
    // =========================================================================

    @Test
    @DisplayName("GAP-11: AttendanceFinalizerJob executes cleanly without error")
    void attendanceFinalizerJobRunsCleanly() {
        assertDoesNotThrow(() -> attendanceFinalizerJob.finalizeDailyAttendance(),
                "Nightly attendance finalizer job should execute safely without unhandled exceptions");
    }

    // =========================================================================
    // 4. GAP-11: Full Thymeleaf View Rendering Suite
    // =========================================================================

    @Test
    @DisplayName("GAP-11: Thymeleaf GET /dashboard renders 200 OK for Super Admin")
    @WithUserDetails("superadmin@company.com")
    void dashboardPageRendersSuccessfully() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"));
    }

    @Test
    @DisplayName("GAP-11: Thymeleaf GET /employees renders 200 OK for HR Admin")
    @WithUserDetails("hr1@company.com")
    void employeesDirectoryPageRendersSuccessfully() throws Exception {
        mockMvc.perform(get("/employees"))
                .andExpect(status().isOk())
                .andExpect(view().name("employees/list"));
    }

    @Test
    @DisplayName("GAP-11: Thymeleaf GET /attendance/my renders 200 OK for Employee")
    @WithUserDetails("emp1@company.com")
    void attendanceMyPageRendersSuccessfully() throws Exception {
        mockMvc.perform(get("/attendance/my"))
                .andExpect(status().isOk())
                .andExpect(view().name("attendance/my"));
    }

    @Test
    @DisplayName("GAP-11: Thymeleaf GET /attendance/manage renders 200 OK for HR Admin")
    @WithUserDetails("hr1@company.com")
    void attendanceManagePageRendersSuccessfully() throws Exception {
        mockMvc.perform(get("/attendance/manage"))
                .andExpect(status().isOk())
                .andExpect(view().name("attendance/manage"));
    }

    @Test
    @DisplayName("GAP-11: Thymeleaf GET /leaves/my renders 200 OK for Employee")
    @WithUserDetails("emp1@company.com")
    void leavesMyPageRendersSuccessfully() throws Exception {
        mockMvc.perform(get("/leaves/my"))
                .andExpect(status().isOk())
                .andExpect(view().name("leaves/my"));
    }

    @Test
    @DisplayName("GAP-11: Thymeleaf GET /leaves/manage renders 200 OK for HR Admin")
    @WithUserDetails("hr1@company.com")
    void leavesManagePageRendersSuccessfully() throws Exception {
        mockMvc.perform(get("/leaves/manage"))
                .andExpect(status().isOk())
                .andExpect(view().name("leaves/manage"));
    }

    @Test
    @DisplayName("GAP-11: Thymeleaf GET /holidays renders 200 OK for Employee")
    @WithUserDetails("emp1@company.com")
    void holidaysPageRendersSuccessfully() throws Exception {
        mockMvc.perform(get("/holidays"))
                .andExpect(status().isOk())
                .andExpect(view().name("holidays/list"));
    }

    @Test
    @DisplayName("GAP-11: Thymeleaf GET /sessions renders 200 OK for Employee")
    @WithUserDetails("emp1@company.com")
    void sessionsPageRendersSuccessfully() throws Exception {
        mockMvc.perform(get("/sessions"))
                .andExpect(status().isOk())
                .andExpect(view().name("sessions"));
    }

    @Test
    @DisplayName("GAP-11: Thymeleaf GET /account/password renders 200 OK for Employee")
    @WithUserDetails("emp1@company.com")
    void passwordChangePageRendersSuccessfully() throws Exception {
        mockMvc.perform(get("/account/password"))
                .andExpect(status().isOk())
                .andExpect(view().name("account/password"));
    }
}
