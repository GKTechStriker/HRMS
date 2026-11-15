package com.hrms.controller;

import com.hrms.attendance.entity.Holiday;
import com.hrms.attendance.repository.HolidayRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration regression suite for Phase 4 (GAP-06 through GAP-09).
 */
@SpringBootTest
@Transactional
class Phase4WebAndIntegrationRegressionTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private HolidayRepository holidayRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    // =========================================================================
    // GAP-07: Audit Log Pagination, Filtering, and Streaming CSV Exports
    // =========================================================================

    @Test
    @DisplayName("GAP-07: GET /audit with filter params renders 200 OK and binds pagination model")
    @WithUserDetails("superadmin@company.com")
    void auditLogPageWithFiltersRendersSuccessfully() throws Exception {
        mockMvc.perform(get("/audit")
                        .param("action", "USER_LOGGED_IN")
                        .param("fromDate", "2026-01-01")
                        .param("toDate", "2026-12-31")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("audit"))
                .andExpect(model().attributeExists("auditPage"))
                .andExpect(model().attributeExists("auditLogs"))
                .andExpect(model().attributeExists("currentPage"))
                .andExpect(model().attributeExists("totalPages"))
                .andExpect(model().attributeExists("availableActions"))
                .andExpect(model().attribute("selectedAction", "USER_LOGGED_IN"));
    }

    @Test
    @DisplayName("GAP-07: GET /reports/audit/csv streams CSV attachment")
    @WithUserDetails("superadmin@company.com")
    void reportAuditCsvStreamsResponse() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/reports/audit/csv")
                        .param("action", "USER_LOGGED_IN")
                        .param("fromDate", "2026-01-01")
                        .param("toDate", "2026-12-31"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment; filename=\"audit_trail_")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/csv; charset=UTF-8")));
    }

    @Test
    @DisplayName("GAP-07: GET /reports/employees/csv streams employee directory CSV")
    @WithUserDetails("hr1@company.com")
    void reportEmployeesCsvStreamsResponse() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/reports/employees/csv"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment; filename=\"employees_")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/csv; charset=UTF-8")));
    }

    @Test
    @DisplayName("GAP-07: GET /reports/attendance/csv streams attendance roster CSV")
    @WithUserDetails("hr1@company.com")
    void reportAttendanceCsvStreamsResponse() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/reports/attendance/csv")
                        .param("date", "2026-11-14"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment; filename=\"attendance_")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/csv; charset=UTF-8")));
    }

    // =========================================================================
    // GAP-08: Leave Usage Analytics & Dashboard Charts
    // =========================================================================

    @Test
    @DisplayName("GAP-08: GET /api/analytics/leave-usage returns 200 JSON for HR_ADMIN")
    @WithUserDetails("hr1@company.com")
    void leaveUsageEndpointReturnsJson() throws Exception {
        mockMvc.perform(get("/api/analytics/leave-usage"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("GAP-08: GET /dashboard loads 200 OK with analytics section")
    @WithUserDetails("hr1@company.com")
    void dashboardPageRendersSuccessfully() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"));
    }

    // =========================================================================
    // GAP-09: Company Holiday Calendar & Management Endpoints
    // =========================================================================

    @Test
    @DisplayName("GAP-09: GET /holidays is viewable by all authenticated employees")
    @WithUserDetails("emp1@company.com")
    void holidaysPageViewableByEmployee() throws Exception {
        mockMvc.perform(get("/holidays"))
                .andExpect(status().isOk())
                .andExpect(view().name("holidays/list"))
                .andExpect(model().attributeExists("holidays"))
                .andExpect(model().attributeExists("today"));
    }

    @Test
    @DisplayName("GAP-09: Employee role is forbidden from creating holidays (POST /holidays/new -> 403)")
    @WithUserDetails("emp1@company.com")
    void employeeCannotCreateHoliday() throws Exception {
        mockMvc.perform(post("/holidays/new")
                        .with(csrf())
                        .param("name", "Test Day")
                        .param("holidayDate", "2026-12-25"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GAP-09: HR Admin can create and delete company holidays")
    @WithUserDetails("hr1@company.com")
    void hrAdminCanCreateAndDeleteHoliday() throws Exception {
        LocalDate date = LocalDate.of(2026, 7, 4);
        // Create holiday
        mockMvc.perform(post("/holidays/new")
                        .with(csrf())
                        .param("name", "Summer Company Day")
                        .param("holidayDate", date.toString())
                        .param("recurring", "true")
                        .param("description", "Annual Summer Festival"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/holidays"))
                .andExpect(flash().attributeExists("successMessage"));

        Holiday created = holidayRepository.findByHolidayDate(date).orElse(null);
        assertNotNull(created);
        assertEquals("Summer Company Day", created.getName());
        assertTrue(created.isRecurring());

        // Delete holiday
        mockMvc.perform(post("/holidays/" + created.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/holidays"))
                .andExpect(flash().attributeExists("successMessage"));

        assertFalse(holidayRepository.existsById(created.getId()));
    }
}
