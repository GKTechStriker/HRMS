package com.hrms.config;

import com.hrms.department.entity.Department;
import com.hrms.department.entity.JobRole;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.department.repository.JobRoleRepository;
import com.hrms.employee.dto.EmployeeFormDto;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.employee.service.EmployeeService;
import com.hrms.exception.BusinessException;
import com.hrms.security.SessionManagementService;
import com.hrms.security.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests verifying BUG-01, BUG-03, BUG-05, and BUG-11 behavior.
 */
class Part1ConfigAndTimeUnitTest {

    @Test
    @DisplayName("BUG-01 & BUG-11: TimeFormatHelper converts Instant/OffsetDateTime using business zone and handles nulls")
    void timeFormatHelperFormatsSafely() {
        TimeFormatHelper helper = new TimeFormatHelper(ZoneId.of("Asia/Kolkata"));

        assertEquals("-", helper.formatInstant(null, "dd MMM yyyy"));
        assertEquals("-", helper.formatDateTime(null, "hh:mm a"));
        assertEquals("", helper.formatDateTimeOrEmpty(null, "HH:mm"));
        assertEquals("-", helper.formatDuration(null));
        assertEquals("0h 0m", helper.formatDuration(0));
        assertEquals("2h 15m", helper.formatDuration(135));

        Instant utcInstant = Instant.parse("2026-11-12T04:00:00Z"); // 09:30 AM IST
        assertEquals("12 Nov 2026, 09:30 AM", helper.formatInstant(utcInstant, "dd MMM yyyy, hh:mm a"));

        OffsetDateTime offsetDateTime = OffsetDateTime.of(2026, 11, 12, 4, 0, 0, 0, ZoneOffset.UTC);
        assertEquals("09:30 AM", helper.formatDateTime(offsetDateTime, "hh:mm a"));
        assertEquals("09:30", helper.formatDateTimeOrEmpty(offsetDateTime, "HH:mm"));
    }

    @Test
    @DisplayName("BUG-05: DataInitializer refuses startup seed with blank initial password on empty DB")
    void dataInitializerRejectsBlankDefaultPassword() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.count()).thenReturn(0L);

        DataInitializer initializer = new DataInitializer(
                userRepo,
                mock(DepartmentRepository.class),
                mock(JobRoleRepository.class),
                mock(EmployeeRepository.class),
                mock(PasswordEncoder.class),
                true,
                "   "
        );

        assertThrows(IllegalStateException.class, () -> initializer.run(null));
    }

    @Test
    @DisplayName("BUG-05: EmployeeService rejects account creation when password and default initial password are both blank")
    void employeeServiceRejectsBlankInitialPassword() {
        EmployeeRepository empRepo = mock(EmployeeRepository.class);
        DepartmentRepository deptRepo = mock(DepartmentRepository.class);
        JobRoleRepository roleRepo = mock(JobRoleRepository.class);
        UserRepository userRepo = mock(UserRepository.class);

        UUID deptId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        Department dept = new Department();
        dept.setId(deptId);
        JobRole role = new JobRole();
        role.setId(roleId);
        role.setDepartment(dept);
        when(deptRepo.findById(deptId)).thenReturn(Optional.of(dept));
        when(roleRepo.findById(roleId)).thenReturn(Optional.of(role));
        when(userRepo.existsByEmailIgnoreCase("new@company.com")).thenReturn(false);

        EmployeeService service = new EmployeeService(
                empRepo,
                deptRepo,
                roleRepo,
                userRepo,
                mock(PasswordEncoder.class),
                mock(JdbcTemplate.class),
                mock(SessionManagementService.class),
                ""
        );

        EmployeeFormDto dto = new EmployeeFormDto();
        dto.setFullName("New Joiner");
        dto.setEmail("new@company.com");
        dto.setDepartmentId(deptId);
        dto.setJobRoleId(roleId);
        dto.setDateOfJoining(LocalDate.of(2026, 11, 12));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createEmployee(dto));
        assertTrue(ex.getMessage().contains("Initial password is required"));
    }

    @Test
    @DisplayName("BUG-05: application-prod.yml contains zero hardcoded fallback secrets")
    void productionProfileHasNoFallbackSecrets() throws Exception {
        String prodYaml = Files.readString(Path.of("src/main/resources/application-prod.yml"));
        assertTrue(prodYaml.contains("password: ${DB_PASSWORD}"),
                "Production datasource password must require DB_PASSWORD without fallback default");
        assertTrue(prodYaml.contains("default-initial-password: ${HRMS_DEFAULT_USER_PASSWORD}"),
                "Production initial password must require HRMS_DEFAULT_USER_PASSWORD without fallback default");
        assertFalse(prodYaml.contains("default-initial-password: ${HRMS_DEFAULT_USER_PASSWORD:"),
                "Production config must not define fallback default for HRMS_DEFAULT_USER_PASSWORD");
    }
}
