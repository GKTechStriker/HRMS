package com.hrms.employee.service;

import com.hrms.department.entity.Department;
import com.hrms.department.entity.JobRole;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.department.repository.JobRoleRepository;
import com.hrms.employee.dto.CsvImportResultDto;
import com.hrms.employee.dto.EmployeeFormDto;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.security.CustomUserDetails;
import com.hrms.security.CustomUserDetailsService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration & regression tests for BUG-09 (Session revocation on deactivation / INACTIVE transition)
 * and BUG-10 (RFC-4180 CSV parser, BOM stripping, dynamic header skipping, batch DB email checks).
 */
@SpringBootTest
class EmployeeCsvAndSessionRegressionTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private Filter springSessionRepositoryFilter;

    @Autowired
    private EmployeeCsvImportService csvImportService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private JobRoleRepository jobRoleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

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
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("BUG-10: RFC-4180 CSV line parser handles quoted commas, escaped quotes, and unclosed quotes")
    void parseCsvLineHandlesQuotedAndEscapedFields() {
        List<String> cols = EmployeeCsvImportService.parseCsvLine(
                "\"Doe, Jane\",jane.doe@company.com,+91-9000000001,\"Engineering, Platform\",\"Lead \"\"Core\"\" Engineer\",1994-03-15,2026-01-10,EMPLOYEE");
        assertEquals(8, cols.size());
        assertEquals("Doe, Jane", cols.get(0));
        assertEquals("Engineering, Platform", cols.get(3));
        assertEquals("Lead \"Core\" Engineer", cols.get(4));

        assertThrows(IllegalArgumentException.class, () ->
                EmployeeCsvImportService.parseCsvLine("\"Unclosed Name,user@company.com"));
    }

    @Test
    @DisplayName("BUG-10: dryRunCsv handles UTF-8 BOM, leading comment before header, quoted commas, and DB duplicate emails")
    void dryRunCsvHandlesBomCommentsQuotesAndDuplicates() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String csvContent = "\uFEFF"
                + "# Uploaded via Excel export\n"
                + "full_name,email,phone,department,job_role,date_of_birth,date_of_joining,role\n"
                + "\"Doe, Jane\",jane." + uniqueSuffix + "@company.com,+91-9000000001,\"Engineering, Core\",\"Staff \"\"Backend\"\" Engineer\",1993-04-20,2026-02-01,EMPLOYEE\n"
                + "Duplicate DB User,emp1@company.com,+91-9000000002,Engineering,Software Engineer,1995-01-01,2026-02-01,EMPLOYEE\n"
                + "Duplicate File User,jane." + uniqueSuffix + "@company.com,+91-9000000003,Engineering,Software Engineer,1995-01-01,2026-02-01,EMPLOYEE\n"
                + "\"Broken Quote,bad@company.com,+91-9000000004,Engineering,QA,1995-01-01,2026-02-01,EMPLOYEE\n";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "employees-bom.csv",
                "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8)
        );

        authenticateAs("hr1@company.com");
        try {
            CsvImportResultDto result = csvImportService.dryRunCsv(file);
            assertEquals(4, result.totalRows());
            assertEquals(1, result.validRows());
            assertEquals(3, result.errorCount());

            CsvImportResultDto.RowPreview validRow = result.rows().get(0);
            assertTrue(validRow.valid());
            assertEquals("Doe, Jane", validRow.fullName());
            assertEquals("Engineering, Core", validRow.departmentName());
            assertEquals("Staff \"Backend\" Engineer", validRow.jobRoleTitle());

            assertFalse(result.rows().get(1).valid());
            assertTrue(result.rows().get(1).validationMessage().contains("Email already exists in database"));

            assertFalse(result.rows().get(2).valid());
            assertTrue(result.rows().get(2).validationMessage().contains("Duplicate email within CSV"));

            assertFalse(result.rows().get(3).valid());
            assertTrue(result.rows().get(3).validationMessage().contains("Unclosed quoted field"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("BUG-09: Deactivating employee immediately closes user_sessions, deletes SPRING_SESSION, and invalidates cookie")
    void deactivateEmployeeRevokesActiveHttpSessionImmediately() throws Exception {
        String email = "temp.session." + UUID.randomUUID().toString().substring(0, 8) + "@company.com";
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
        tempEmp.setEmployeeCode("EMP-TMP-" + UUID.randomUUID().toString().substring(0, 5));
        tempEmp.setFullName("Temp Session User");
        tempEmp.setDepartment(dept);
        tempEmp.setJobRole(jobRole);
        tempEmp.setUser(tempUser);
        tempEmp.setDateOfJoining(LocalDate.of(2026, 1, 1));
        tempEmp.setStatus(EmployeeStatus.ACTIVE);
        tempEmp = employeeRepository.save(tempEmp);

        try {
            // Log in through real formLogin + Spring Session JDBC filter
            MvcResult loginResult = mockMvc.perform(post("/login")
                            .with(csrf())
                            .param("username", email)
                            .param("password", rawPass))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/dashboard"))
                    .andReturn();

            Cookie sessionCookie = loginResult.getResponse().getCookie("SESSION");
            assertNotNull(sessionCookie, "Spring Session JDBC must issue SESSION cookie on login");

            // Confirm active session works before deactivation
            mockMvc.perform(get("/dashboard").cookie(sessionCookie))
                    .andExpect(status().isOk());

            Integer beforeCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM SPRING_SESSION WHERE LOWER(PRINCIPAL_NAME) = LOWER(?)",
                    Integer.class, email);
            assertNotNull(beforeCount);
            assertTrue(beforeCount >= 1, "Active SPRING_SESSION row must exist prior to deactivation");

            // Deactivate as HR admin (suspendLogin = true)
            authenticateAs("hr1@company.com");
            employeeService.deactivateEmployee(tempEmp.getId(), true, "Separation finalized");
            SecurityContextHolder.clearContext();

            // Verify user disabled, tracked UserSession closed as REVOKED, SPRING_SESSION deleted
            User updatedUser = userRepository.findById(tempUser.getId()).orElseThrow();
            assertFalse(updatedUser.isActive());

            List<UserSession> userSessions = userSessionRepository.findByUserIdOrderByLoginAtDesc(tempUser.getId());
            assertFalse(userSessions.isEmpty());
            assertTrue(userSessions.stream().allMatch(us ->
                    us.getLogoutAt() != null && us.getEndedReason() == SessionEndedReason.REVOKED));

            Integer afterCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM SPRING_SESSION WHERE LOWER(PRINCIPAL_NAME) = LOWER(?)",
                    Integer.class, email);
            assertEquals(0, afterCount, "All SPRING_SESSION rows for deactivated user must be deleted");

            // Subsequent HTTP request carrying old SESSION cookie must be rejected to /login
            mockMvc.perform(get("/dashboard").cookie(sessionCookie))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login"));
        } finally {
            SecurityContextHolder.clearContext();
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
    @DisplayName("BUG-09: Updating employee status to INACTIVE via updateEmployee also suspends user and revokes sessions")
    void updateEmployeeToInactiveRevokesActiveSession() throws Exception {
        String email = "temp.update." + UUID.randomUUID().toString().substring(0, 8) + "@company.com";
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
        tempEmp.setEmployeeCode("EMP-UPD-" + UUID.randomUUID().toString().substring(0, 5));
        tempEmp.setFullName("Temp Update User");
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
                    .andReturn();

            Cookie sessionCookie = loginResult.getResponse().getCookie("SESSION");
            assertNotNull(sessionCookie);

            EmployeeFormDto updateDto = new EmployeeFormDto();
            updateDto.setFullName(tempEmp.getFullName());
            updateDto.setDepartmentId(dept.getId());
            updateDto.setJobRoleId(jobRole.getId());
            updateDto.setDateOfJoining(tempEmp.getDateOfJoining());
            updateDto.setStatus(EmployeeStatus.INACTIVE);

            authenticateAs("hr1@company.com");
            employeeService.updateEmployee(tempEmp.getId(), updateDto);
            SecurityContextHolder.clearContext();

            User updatedUser = userRepository.findById(tempUser.getId()).orElseThrow();
            assertFalse(updatedUser.isActive(), "User active flag must become false when status changes to INACTIVE");

            Integer remaining = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM SPRING_SESSION WHERE LOWER(PRINCIPAL_NAME) = LOWER(?)",
                    Integer.class, email);
            assertEquals(0, remaining);

            mockMvc.perform(get("/dashboard").cookie(sessionCookie))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login"));
        } finally {
            SecurityContextHolder.clearContext();
            jdbcTemplate.update("DELETE FROM SPRING_SESSION WHERE LOWER(PRINCIPAL_NAME) = LOWER(?)", email);
            List<UserSession> sessions = userSessionRepository.findByUserIdOrderByLoginAtDesc(tempUser.getId());
            if (!sessions.isEmpty()) {
                userSessionRepository.deleteAll(sessions);
            }
            employeeRepository.findById(tempEmp.getId()).ifPresent(employeeRepository::delete);
            userRepository.findById(tempUser.getId()).ifPresent(userRepository::delete);
        }
    }

    private void authenticateAs(String email) {
        CustomUserDetails details = (CustomUserDetails) customUserDetailsService.loadUserByUsername(email);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }
}
