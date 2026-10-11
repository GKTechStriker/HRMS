package com.hrms.employee.service;

import com.hrms.audit.AuditContext;
import com.hrms.audit.Audited;
import com.hrms.department.entity.Department;
import com.hrms.department.entity.JobRole;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.department.repository.JobRoleRepository;
import com.hrms.employee.dto.EmployeeFormDto;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.exception.BusinessException;
import com.hrms.exception.ResourceNotFoundException;
import com.hrms.security.CustomUserDetails;
import com.hrms.security.entity.Role;
import com.hrms.security.entity.User;
import com.hrms.security.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Employee lifecycle management service (Section 6.2):
 * - Concurrency-safe sequential EMP-xxx and HR-xxx code allocation
 * - Full CRUD with field-level before/after JSONB audit snapshots
 * - Soft deactivation preserving historical records
 */
@Service
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final JobRoleRepository jobRoleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final String defaultInitialPassword;

    public EmployeeService(EmployeeRepository employeeRepository,
                           DepartmentRepository departmentRepository,
                           JobRoleRepository jobRoleRepository,
                           UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           JdbcTemplate jdbcTemplate,
                           @Value("${hrms.security.default-initial-password}") String defaultInitialPassword) {
        this.employeeRepository = employeeRepository;
        this.departmentRepository = departmentRepository;
        this.jobRoleRepository = jobRoleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.defaultInitialPassword = defaultInitialPassword;
    }

    /**
     * Allocates the next unique sequential employee code (EMP-001, EMP-002, ...)
     * using PostgreSQL sequence seq_employee_code so concurrent requests never collide.
     */
    @Transactional
    public String allocateNextEmployeeCode() {
        String code;
        do {
            Long nextVal = jdbcTemplate.queryForObject("SELECT nextval('seq_employee_code')", Long.class);
            long seq = nextVal != null ? nextVal : 1L;
            code = String.format("EMP-%03d", seq);
        } while (employeeRepository.existsByEmployeeCode(code));
        return code;
    }

    /**
     * Allocates the next unique HR code (HR-001, HR-002, ...) for HR_ADMIN accounts.
     */
    @Transactional
    public String allocateNextHrCode() {
        String code;
        do {
            Long nextVal = jdbcTemplate.queryForObject("SELECT nextval('seq_hr_code')", Long.class);
            long seq = nextVal != null ? nextVal : 1L;
            code = String.format("HR-%03d", seq);
        } while (userRepository.findByHrCode(code).isPresent());
        return code;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public Page<Employee> searchDirectory(String search,
                                          UUID departmentId,
                                          UUID jobRoleId,
                                          EmployeeStatus status,
                                          Pageable pageable) {
        return employeeRepository.searchDirectory(search, departmentId, jobRoleId, status, pageable);
    }

    /**
     * Retrieves an employee profile, enforcing self-service scoping (Section 4.1 & 8.3):
     * - SUPER_ADMIN and HR_ADMIN can view any employee
     * - EMPLOYEE can view ONLY their own employee record
     */
    @Transactional(readOnly = true)
    public Employee getEmployeeForPrincipal(UUID employeeId, CustomUserDetails principal) {
        Employee emp = employeeRepository.findByIdWithDetails(employeeId)
                .orElseThrow(() -> ResourceNotFoundException.forEntity("Employee", employeeId.toString()));

        if (principal.getRole() == Role.EMPLOYEE) {
            if (emp.getUser() == null || !emp.getUser().getId().equals(principal.getUserId())) {
                throw new AccessDeniedException("Employees may only view their own profile.");
            }
        }
        return emp;
    }

    @Transactional
    @PreAuthorize("hasRole('HR_ADMIN')")
    @Audited(action = "EMPLOYEE_CREATED", targetType = "Employee")
    public Employee createEmployee(EmployeeFormDto dto) {
        Department dept = departmentRepository.findById(dto.getDepartmentId())
                .orElseThrow(() -> ResourceNotFoundException.forEntity("Department", dto.getDepartmentId().toString()));
        JobRole jobRole = jobRoleRepository.findById(dto.getJobRoleId())
                .orElseThrow(() -> ResourceNotFoundException.forEntity("JobRole", dto.getJobRoleId().toString()));

        User linkedUser = null;
        if (dto.getEmail() != null && !dto.getEmail().isBlank()) {
            String normalizedEmail = dto.getEmail().trim().toLowerCase();
            if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
                throw new BusinessException("A user account already exists with email: " + normalizedEmail);
            }
            linkedUser = new User();
            linkedUser.setEmail(normalizedEmail);
            String rawPassword = (dto.getPassword() != null && !dto.getPassword().isBlank())
                    ? dto.getPassword()
                    : defaultInitialPassword;
            linkedUser.setPasswordHash(passwordEncoder.encode(rawPassword));
            Role assignedRole = dto.getSystemRole() != null ? dto.getSystemRole() : Role.EMPLOYEE;
            if (assignedRole == Role.SUPER_ADMIN) {
                throw new BusinessException("HR Admin cannot create SUPER_ADMIN accounts.");
            }
            linkedUser.setRole(assignedRole);
            if (assignedRole == Role.HR_ADMIN) {
                linkedUser.setHrCode(allocateNextHrCode());
            }
            linkedUser.setActive(true);
            linkedUser = userRepository.save(linkedUser);
        }

        Employee emp = new Employee();
        emp.setEmployeeCode(allocateNextEmployeeCode());
        emp.setFullName(dto.getFullName().trim());
        emp.setDepartment(dept);
        emp.setJobRole(jobRole);
        emp.setUser(linkedUser);
        emp.setPhone(dto.getPhone() != null ? dto.getPhone().trim() : null);
        emp.setDateOfBirth(dto.getDateOfBirth());
        emp.setDateOfJoining(dto.getDateOfJoining());
        emp.setStatus(dto.getStatus() != null ? dto.getStatus() : EmployeeStatus.ACTIVE);

        if (dto.getReportsToId() != null) {
            Employee manager = employeeRepository.findById(dto.getReportsToId())
                    .orElseThrow(() -> ResourceNotFoundException.forEntity("Employee", dto.getReportsToId().toString()));
            emp.setReportsTo(manager);
        }

        emp = employeeRepository.save(emp);

        AuditContext.setTarget(emp.getId(), null, toAuditSnapshot(emp));
        return emp;
    }

    @Transactional
    @PreAuthorize("hasRole('HR_ADMIN')")
    @Audited(action = "EMPLOYEE_UPDATED", targetType = "Employee")
    public Employee updateEmployee(UUID employeeId, EmployeeFormDto dto) {
        Employee emp = employeeRepository.findByIdWithDetails(employeeId)
                .orElseThrow(() -> ResourceNotFoundException.forEntity("Employee", employeeId.toString()));

        Map<String, Object> beforeSnapshot = toAuditSnapshot(emp);

        Department dept = departmentRepository.findById(dto.getDepartmentId())
                .orElseThrow(() -> ResourceNotFoundException.forEntity("Department", dto.getDepartmentId().toString()));
        JobRole jobRole = jobRoleRepository.findById(dto.getJobRoleId())
                .orElseThrow(() -> ResourceNotFoundException.forEntity("JobRole", dto.getJobRoleId().toString()));

        emp.setFullName(dto.getFullName().trim());
        emp.setDepartment(dept);
        emp.setJobRole(jobRole);
        emp.setPhone(dto.getPhone() != null ? dto.getPhone().trim() : null);
        emp.setDateOfBirth(dto.getDateOfBirth());
        emp.setDateOfJoining(dto.getDateOfJoining());
        if (dto.getStatus() != null) {
            emp.setStatus(dto.getStatus());
        }

        if (dto.getReportsToId() != null && !dto.getReportsToId().equals(emp.getId())) {
            Employee manager = employeeRepository.findById(dto.getReportsToId())
                    .orElseThrow(() -> ResourceNotFoundException.forEntity("Employee", dto.getReportsToId().toString()));
            emp.setReportsTo(manager);
        } else {
            emp.setReportsTo(null);
        }

        emp = employeeRepository.save(emp);
        Map<String, Object> afterSnapshot = toAuditSnapshot(emp);

        AuditContext.setTarget(emp.getId(), beforeSnapshot, afterSnapshot);
        return emp;
    }

    @Transactional
    @PreAuthorize("hasRole('HR_ADMIN')")
    @Audited(action = "EMPLOYEE_DEACTIVATED", targetType = "Employee")
    public Employee deactivateEmployee(UUID employeeId, boolean suspendLogin, String reason) {
        Employee emp = employeeRepository.findByIdWithDetails(employeeId)
                .orElseThrow(() -> ResourceNotFoundException.forEntity("Employee", employeeId.toString()));

        Map<String, Object> beforeSnapshot = toAuditSnapshot(emp);

        emp.setStatus(EmployeeStatus.INACTIVE);
        if (suspendLogin && emp.getUser() != null) {
            User user = emp.getUser();
            user.setActive(false);
            userRepository.save(user);
        }
        emp = employeeRepository.save(emp);

        Map<String, Object> afterSnapshot = toAuditSnapshot(emp);
        afterSnapshot.put("deactivationReason", reason != null && !reason.isBlank() ? reason.trim() : "HR Deactivation");
        afterSnapshot.put("loginSuspended", suspendLogin);

        AuditContext.setTarget(emp.getId(), beforeSnapshot, afterSnapshot);
        return emp;
    }

    private Map<String, Object> toAuditSnapshot(Employee emp) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("employeeCode", emp.getEmployeeCode());
        map.put("fullName", emp.getFullName());
        map.put("email", emp.getUser() != null ? emp.getUser().getEmail() : null);
        map.put("department", emp.getDepartment() != null ? emp.getDepartment().getName() : null);
        map.put("jobRole", emp.getJobRole() != null ? emp.getJobRole().getTitle() : null);
        map.put("phone", emp.getPhone());
        map.put("dateOfBirth", emp.getDateOfBirth() != null ? emp.getDateOfBirth().toString() : null);
        map.put("dateOfJoining", emp.getDateOfJoining() != null ? emp.getDateOfJoining().toString() : null);
        map.put("status", emp.getStatus().name());
        return map;
    }
}
