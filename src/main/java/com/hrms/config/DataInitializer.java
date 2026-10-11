package com.hrms.config;

import com.hrms.department.entity.Department;
import com.hrms.department.entity.JobRole;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.department.repository.JobRoleRepository;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.security.entity.Role;
import com.hrms.security.entity.User;
import com.hrms.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Seeds baseline organizational structure and initial role accounts
 * (SUPER_ADMIN, HR_ADMIN HR-001, EMPLOYEE EMP-001) on first startup when enabled.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final JobRoleRepository jobRoleRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean seedEnabled;
    private final String defaultInitialPassword;

    public DataInitializer(UserRepository userRepository,
                           DepartmentRepository departmentRepository,
                           JobRoleRepository jobRoleRepository,
                           EmployeeRepository employeeRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${hrms.seed.enabled:true}") boolean seedEnabled,
                           @Value("${hrms.security.default-initial-password}") String defaultInitialPassword) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.jobRoleRepository = jobRoleRepository;
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedEnabled = seedEnabled;
        this.defaultInitialPassword = defaultInitialPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("Initial data seeding is disabled (hrms.seed.enabled=false).");
            return;
        }
        if (userRepository.count() > 0) {
            log.info("Database already contains {} user(s); skipping initial seed.", userRepository.count());
            return;
        }

        log.info("Seeding initial HRMS departments, job roles, and role accounts...");

        // 1. Seed Departments
        Department hrDept = new Department();
        hrDept.setName("Human Resources");
        hrDept = departmentRepository.save(hrDept);

        Department engDept = new Department();
        engDept.setName("Engineering");
        engDept = departmentRepository.save(engDept);

        Department opsDept = new Department();
        opsDept.setName("Operations");
        opsDept = departmentRepository.save(opsDept);

        // 2. Seed Job Roles
        JobRole hrManagerRole = new JobRole();
        hrManagerRole.setTitle("HR Specialist");
        hrManagerRole.setLevel(3);
        hrManagerRole.setDepartment(hrDept);
        hrManagerRole = jobRoleRepository.save(hrManagerRole);

        JobRole sweRole = new JobRole();
        sweRole.setTitle("Software Engineer");
        sweRole.setLevel(2);
        sweRole.setDepartment(engDept);
        sweRole = jobRoleRepository.save(sweRole);

        JobRole opsAnalystRole = new JobRole();
        opsAnalystRole.setTitle("Operations Analyst");
        opsAnalystRole.setLevel(2);
        opsAnalystRole.setDepartment(opsDept);
        jobRoleRepository.save(opsAnalystRole);

        String encodedPassword = passwordEncoder.encode(defaultInitialPassword);

        // 3. Seed Super Admin (Leadership Oversight - Read-only + Session Revocation)
        User superAdmin = new User();
        superAdmin.setEmail("superadmin@company.com");
        superAdmin.setPasswordHash(encodedPassword);
        superAdmin.setRole(Role.SUPER_ADMIN);
        superAdmin.setActive(true);
        userRepository.save(superAdmin);

        // 4. Seed HR Admin (HR-001 - Operational HR)
        User hrAdmin = new User();
        hrAdmin.setEmail("hr1@company.com");
        hrAdmin.setPasswordHash(encodedPassword);
        hrAdmin.setRole(Role.HR_ADMIN);
        hrAdmin.setHrCode("HR-001");
        hrAdmin.setActive(true);
        hrAdmin = userRepository.save(hrAdmin);

        Employee hrEmp = new Employee();
        hrEmp.setUser(hrAdmin);
        hrEmp.setDepartment(hrDept);
        hrEmp.setJobRole(hrManagerRole);
        hrEmp.setEmployeeCode("EMP-001");
        hrEmp.setFullName("Priya Sharma");
        hrEmp.setPhone("+91-9876543210");
        hrEmp.setDateOfBirth(LocalDate.of(1992, 5, 14));
        hrEmp.setDateOfJoining(LocalDate.of(2023, 1, 10));
        hrEmp.setStatus(EmployeeStatus.ACTIVE);
        employeeRepository.save(hrEmp);

        // 5. Seed Employee (EMP-002 - Self-service Employee)
        User empUser = new User();
        empUser.setEmail("emp1@company.com");
        empUser.setPasswordHash(encodedPassword);
        empUser.setRole(Role.EMPLOYEE);
        empUser.setActive(true);
        empUser = userRepository.save(empUser);

        Employee empRecord = new Employee();
        empRecord.setUser(empUser);
        empRecord.setDepartment(engDept);
        empRecord.setJobRole(sweRole);
        empRecord.setEmployeeCode("EMP-002");
        empRecord.setFullName("Aarav Mehta");
        empRecord.setPhone("+91-9876543211");
        empRecord.setDateOfBirth(LocalDate.of(1997, 8, 22));
        empRecord.setDateOfJoining(LocalDate.of(2024, 3, 1));
        empRecord.setStatus(EmployeeStatus.ACTIVE);
        employeeRepository.save(empRecord);

        log.info("Seed complete! Default accounts created (initial password loaded from HRMS_DEFAULT_USER_PASSWORD):");
        log.info("  - SUPER_ADMIN : superadmin@company.com");
        log.info("  - HR_ADMIN    : hr1@company.com (HR-001 / EMP-001)");
        log.info("  - EMPLOYEE    : emp1@company.com (EMP-002)");
    }
}
