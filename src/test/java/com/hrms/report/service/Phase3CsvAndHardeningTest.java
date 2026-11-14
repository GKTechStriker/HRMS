package com.hrms.report.service;

import com.hrms.audit.AuditAspect;
import com.hrms.department.entity.Department;
import com.hrms.department.entity.JobRole;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.department.repository.JobRoleRepository;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 Regression Tests covering:
 * - GAP-04: CSV formula injection defense in ExportService
 * - GAP-05: Error sanitization, spoofed proxy header ignoring, CORS hardening, and prod profile config
 */
@SpringBootTest
class Phase3CsvAndHardeningTest {

    @Autowired
    private ExportService exportService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private JobRoleRepository jobRoleRepository;

    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    @Test
    @DisplayName("GAP-04: ExportService escapes spreadsheet formula triggers (=, +, -, @, tab, cr, leading spaces)")
    void exportServiceEscapesFormulaInjectionTriggers() {
        assertEquals("", exportService.escape(null));
        assertEquals("Alice Smith", exportService.escape("Alice Smith"));
        assertEquals("'=cmd|'/C calc'!A0", exportService.escape("=cmd|'/C calc'!A0"));
        assertEquals("'+1+1", exportService.escape("+1+1"));
        assertEquals("'-2+3", exportService.escape("-2+3"));
        assertEquals("'@NOW()", exportService.escape("@NOW()"));
        assertEquals("\"'@SUM(1,1)\"", exportService.escape("@SUM(1,1)"));
        assertEquals("'\t=1+1", exportService.escape("\t=1+1"));
        assertEquals("\"'\r=1+1\"", exportService.escape("\r=1+1"));
        assertEquals("'  =1+1", exportService.escape("  =1+1"));
        assertEquals("\"'=HYPERLINK(\"\"http://evil\"\")\"",
                exportService.escape("=HYPERLINK(\"http://evil\")"));
        assertEquals("\"'+1,2\"", exportService.escape("+1,2"));
    }

    @Test
    @DisplayName("GAP-04: generateEmployeesCsv neutralizes formula payloads persisted in employee fields")
    @WithUserDetails("hr1@company.com")
    void generateEmployeesCsvNeutralizesDangerousEmployeeFields() {
        Department dept = departmentRepository.findAll().get(0);
        JobRole role = jobRoleRepository.findByDepartmentId(dept.getId()).get(0);

        String marker = "FormulaCheck-" + UUID.randomUUID().toString().substring(0, 6);
        Employee malicious = new Employee();
        malicious.setEmployeeCode("EMP-CSV-" + UUID.randomUUID().toString().substring(0, 5));
        malicious.setFullName("=SUM(1,2) " + marker);
        malicious.setPhone("+91-9876543210");
        malicious.setDepartment(dept);
        malicious.setJobRole(role);
        malicious.setDateOfJoining(LocalDate.of(2026, 2, 1));
        malicious.setStatus(EmployeeStatus.ACTIVE);
        malicious = employeeRepository.saveAndFlush(malicious);

        try {
            String csv = new String(exportService.generateEmployeesCsv(), StandardCharsets.UTF_8);
            assertTrue(csv.contains("\"'=SUM(1,2) " + marker + "\""),
                    "Formula trigger with comma must be prefixed with apostrophe and quoted");
            assertTrue(csv.contains("'+91-9876543210"),
                    "Leading plus sign phone number must be prefixed with apostrophe in CSV");
            assertFalse(csv.contains(",=SUM(1,2)"), "Raw unescaped formula must never appear in CSV cell");
        } finally {
            employeeRepository.deleteById(malicious.getId());
        }
    }

    @Test
    @DisplayName("GAP-05: GlobalExceptionHandler returns generic 500 message without leaking exception text")
    void globalExceptionHandlerSanitizes500Response() {
        MockHttpServletRequest servletReq = new MockHttpServletRequest("GET", "/api/analytics/headcount-by-dept");
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = globalExceptionHandler.handleGlobalException(
                new RuntimeException("FATAL: password authentication failed for jdbc:postgresql://10.0.0.5:5432/hrms"),
                new ServletWebRequest(servletReq)
        );

        assertEquals(500, response.getStatusCode().value());
        assertNotNull(response.getBody());
        String msg = response.getBody().getMessage();
        assertEquals("An unexpected internal error occurred. Please try again later.", msg);
        assertFalse(msg.contains("postgresql"));
        assertFalse(msg.contains("password authentication failed"));
    }

    @Test
    @DisplayName("GAP-05: AuditAspect.extractClientIp ignores spoofed X-Forwarded-For and X-Real-IP headers")
    void extractClientIpIgnoresSpoofedProxyHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.42");
        request.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.1.1");
        request.addHeader("X-Real-IP", "172.16.0.5");

        assertEquals("203.0.113.42", AuditAspect.extractClientIp(request));
    }

    @Test
    @DisplayName("GAP-05: CORS configuration rejects wildcard origins by default")
    void corsConfigurationDisallowsWildcardOriginByDefault() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/analytics/headcount-by-dept");
        CorsConfiguration cors = corsConfigurationSource.getCorsConfiguration(request);
        assertNotNull(cors);
        assertNotNull(cors.getAllowedOrigins());
        assertFalse(cors.getAllowedOrigins().contains("*"), "Wildcard '*' origin must never be enabled");
        assertTrue(cors.getAllowedOrigins().isEmpty(), "CORS allowed origins should be empty unless configured");
        assertFalse(Boolean.TRUE.equals(cors.getAllowCredentials()));
    }

    @Test
    @DisplayName("GAP-05: Production profile configures hardened logging, error suppression, and non-fallback secrets")
    void productionProfileIsHardened() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));
        Properties props = yaml.getObject();
        assertNotNull(props);

        assertEquals("false", props.getProperty("spring.jpa.show-sql"));
        assertEquals("never", props.getProperty("server.error.include-message"));
        assertEquals("never", props.getProperty("server.error.include-stacktrace"));
        assertEquals("false", props.getProperty("server.error.include-exception"));
        assertEquals("true", props.getProperty("server.servlet.session.cookie.secure"));
        assertEquals("false", props.getProperty("hrms.demo-hints.enabled"));
        assertEquals("WARN", props.getProperty("logging.level.org.hibernate.SQL"));
        assertEquals("WARN", props.getProperty("logging.level.org.springframework.security"));
        assertEquals("${DB_PASSWORD}", props.getProperty("spring.datasource.password"));
        assertEquals("${HRMS_DEFAULT_USER_PASSWORD}", props.getProperty("hrms.security.default-initial-password"));
    }
}
