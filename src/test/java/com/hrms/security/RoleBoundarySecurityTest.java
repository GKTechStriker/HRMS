package com.hrms.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 4 System Hardening & Role Boundary Verification Suite (Section 4 & Section 9.4).
 *
 * Verifies strict boundary separation:
 * 1. SUPER_ADMIN is strictly read-only on HR domain data (POST mutations receive 403 Forbidden).
 * 2. SUPER_ADMIN has oversight access (GET queries succeed).
 * 3. HR_ADMIN possesses operational read/write access.
 * 4. EMPLOYEE is strictly confined to self-service endpoints and forbidden from administrative queues and reports.
 */
@SpringBootTest
class RoleBoundarySecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    // =========================================================================
    // 1. SUPER_ADMIN Zero-Write Constraint Enforcement
    // =========================================================================

    @Test
    @DisplayName("Super Admin receives 403 Forbidden on employee mutation POST /employees/new")
    @WithMockUser(username = "admin@company.com", roles = {"SUPER_ADMIN"})
    void superAdminCannotMutateEmployees() throws Exception {
        mockMvc.perform(post("/employees/new").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Super Admin receives 403 Forbidden on department mutation POST /departments/new")
    @WithMockUser(username = "admin@company.com", roles = {"SUPER_ADMIN"})
    void superAdminCannotMutateDepartments() throws Exception {
        mockMvc.perform(post("/departments/new").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Super Admin receives 403 Forbidden on manual attendance correction POST /attendance/correct")
    @WithMockUser(username = "admin@company.com", roles = {"SUPER_ADMIN"})
    void superAdminCannotCorrectAttendance() throws Exception {
        mockMvc.perform(post("/attendance/correct").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Super Admin receives 403 Forbidden on leave approval POST /leaves/{id}/approve")
    @WithMockUser(username = "admin@company.com", roles = {"SUPER_ADMIN"})
    void superAdminCannotApproveLeaves() throws Exception {
        mockMvc.perform(post("/leaves/00000000-0000-0000-0000-000000000000/approve").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Super Admin has read oversight on Employee Directory GET /employees")
    @WithMockUser(username = "admin@company.com", roles = {"SUPER_ADMIN"})
    void superAdminCanReadEmployeeDirectory() throws Exception {
        mockMvc.perform(get("/employees"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Super Admin has read oversight on Attendance Roster GET /attendance/manage")
    @WithMockUser(username = "admin@company.com", roles = {"SUPER_ADMIN"})
    void superAdminCanReadAttendanceRoster() throws Exception {
        mockMvc.perform(get("/attendance/manage"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Super Admin has read oversight on Leave Queue GET /leaves/manage")
    @WithMockUser(username = "admin@company.com", roles = {"SUPER_ADMIN"})
    void superAdminCanReadLeaveQueue() throws Exception {
        mockMvc.perform(get("/leaves/manage"))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // 2. HR_ADMIN Operational Permissions
    // =========================================================================

    @Test
    @DisplayName("HR Admin can access employee creation form GET /employees/new")
    @WithMockUser(username = "hr1@company.com", roles = {"HR_ADMIN"})
    void hrAdminCanAccessEmployeeCreateForm() throws Exception {
        mockMvc.perform(get("/employees/new"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("HR Admin can access attendance management GET /attendance/manage")
    @WithMockUser(username = "hr1@company.com", roles = {"HR_ADMIN"})
    void hrAdminCanAccessAttendanceManage() throws Exception {
        mockMvc.perform(get("/attendance/manage"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("HR Admin can access leave approval queue GET /leaves/manage")
    @WithMockUser(username = "hr1@company.com", roles = {"HR_ADMIN"})
    void hrAdminCanAccessLeaveManage() throws Exception {
        mockMvc.perform(get("/leaves/manage"))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // 3. EMPLOYEE Self-Service Boundary Restrictions
    // =========================================================================

    @Test
    @DisplayName("Employee receives 403 Forbidden when accessing Employee Directory GET /employees")
    @WithMockUser(username = "emp1@company.com", roles = {"EMPLOYEE"})
    void employeeCannotAccessEmployeeDirectory() throws Exception {
        mockMvc.perform(get("/employees"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Employee receives 403 Forbidden when accessing Attendance Management GET /attendance/manage")
    @WithMockUser(username = "emp1@company.com", roles = {"EMPLOYEE"})
    void employeeCannotAccessAttendanceManage() throws Exception {
        mockMvc.perform(get("/attendance/manage"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Employee receives 403 Forbidden when accessing Leave Queue GET /leaves/manage")
    @WithMockUser(username = "emp1@company.com", roles = {"EMPLOYEE"})
    void employeeCannotAccessLeaveQueue() throws Exception {
        mockMvc.perform(get("/leaves/manage"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Employee receives 403 Forbidden when accessing Audit Trail GET /audit")
    @WithMockUser(username = "emp1@company.com", roles = {"EMPLOYEE"})
    void employeeCannotAccessAuditTrail() throws Exception {
        mockMvc.perform(get("/audit"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Employee receives 403 Forbidden when downloading Employee CSV GET /reports/employees/csv")
    @WithMockUser(username = "emp1@company.com", roles = {"EMPLOYEE"})
    void employeeCannotDownloadEmployeeCsv() throws Exception {
        mockMvc.perform(get("/reports/employees/csv"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Employee can access self-service attendance clock GET /attendance/my")
    @WithUserDetails("emp1@company.com")
    void employeeCanAccessMyAttendance() throws Exception {
        mockMvc.perform(get("/attendance/my"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Employee can access self-service leave history GET /leaves/my")
    @WithUserDetails("emp1@company.com")
    void employeeCanAccessMyLeaves() throws Exception {
        mockMvc.perform(get("/leaves/my"))
                .andExpect(status().isOk());
    }
}
