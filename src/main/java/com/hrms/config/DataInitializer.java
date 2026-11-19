package com.hrms.config;

import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.attendance.entity.WorkMode;
import com.hrms.attendance.repository.AttendanceRecordRepository;
import com.hrms.audit.entity.AuditLog;
import com.hrms.audit.repository.AuditLogRepository;
import com.hrms.department.entity.Department;
import com.hrms.department.entity.JobRole;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.department.repository.JobRoleRepository;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.leave.entity.LeaveBalance;
import com.hrms.leave.entity.LeaveRequest;
import com.hrms.leave.entity.LeaveStatus;
import com.hrms.leave.entity.LeaveType;
import com.hrms.leave.repository.LeaveBalanceRepository;
import com.hrms.leave.repository.LeaveRequestRepository;
import com.hrms.leave.repository.LeaveTypeRepository;
import com.hrms.security.entity.Role;
import com.hrms.security.entity.User;
import com.hrms.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * Seeds baseline organizational structure and realistic demo test data:
 * - Departments & diverse job roles across levels 1 to 5
 * - Full organizational hierarchy with reporting lines & department heads
 * - Super Admin, HR Admins, and Employees across departments
 * - Annual leave quotas & running balances (with provisional holds)
 * - Diverse leave requests (PENDING, APPROVED, REJECTED)
 * - 14-day historical attendance records + active today clock-in punches
 * - Immutable audit trail entries for system history
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final JobRoleRepository jobRoleRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final boolean seedEnabled;
    private final boolean seedDemoData;
    private final String defaultInitialPassword;

    @Autowired
    public DataInitializer(UserRepository userRepository,
                           DepartmentRepository departmentRepository,
                           JobRoleRepository jobRoleRepository,
                           EmployeeRepository employeeRepository,
                           @Autowired(required = false) LeaveTypeRepository leaveTypeRepository,
                           @Autowired(required = false) LeaveBalanceRepository leaveBalanceRepository,
                           @Autowired(required = false) LeaveRequestRepository leaveRequestRepository,
                           @Autowired(required = false) AttendanceRecordRepository attendanceRecordRepository,
                           @Autowired(required = false) AuditLogRepository auditLogRepository,
                           PasswordEncoder passwordEncoder,
                           @Autowired(required = false) JdbcTemplate jdbcTemplate,
                           @Autowired(required = false) Clock clock,
                           @Value("${hrms.seed.enabled:true}") boolean seedEnabled,
                           @Value("${hrms.seed.demo-data:true}") boolean seedDemoData,
                           @Value("${hrms.security.default-initial-password:}") String defaultInitialPassword) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.jobRoleRepository = jobRoleRepository;
        this.employeeRepository = employeeRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.leaveBalanceRepository = leaveBalanceRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        this.seedEnabled = seedEnabled;
        this.seedDemoData = seedDemoData;
        this.defaultInitialPassword = defaultInitialPassword;
    }

    /**
     * Backward-compatible constructor for existing unit tests.
     */
    public DataInitializer(UserRepository userRepository,
                           DepartmentRepository departmentRepository,
                           JobRoleRepository jobRoleRepository,
                           EmployeeRepository employeeRepository,
                           PasswordEncoder passwordEncoder,
                           boolean seedEnabled,
                           String defaultInitialPassword) {
        this(userRepository, departmentRepository, jobRoleRepository, employeeRepository,
             null, null, null, null, null, passwordEncoder, null, Clock.systemDefaultZone(),
             seedEnabled, true, defaultInitialPassword);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("Initial data seeding is disabled (hrms.seed.enabled=false).");
            return;
        }
        if (defaultInitialPassword == null || defaultInitialPassword.isBlank()) {
            throw new IllegalStateException(
                    "HRMS_DEFAULT_USER_PASSWORD (hrms.security.default-initial-password) must not be blank when initial seeding is enabled.");
        }

        String encodedPassword = passwordEncoder.encode(defaultInitialPassword);

        // 1. Ensure Baseline Accounts & Departments Exist
        seedBaseline(encodedPassword);

        // 2. Seed Rich Demo Test Data (Employees, Balances, Leaves, Attendance, Audit)
        if (seedDemoData && leaveTypeRepository != null) {
            seedDemoData(encodedPassword);
        }
    }

    private void seedBaseline(String encodedPassword) {
        // Human Resources
        Department hrDept = departmentRepository.findByNameIgnoreCase("Human Resources")
                .orElseGet(() -> {
                    Department d = new Department();
                    d.setName("Human Resources");
                    return departmentRepository.save(d);
                });

        // Engineering
        Department engDept = departmentRepository.findByNameIgnoreCase("Engineering")
                .orElseGet(() -> {
                    Department d = new Department();
                    d.setName("Engineering");
                    return departmentRepository.save(d);
                });

        // Operations
        Department opsDept = departmentRepository.findByNameIgnoreCase("Operations")
                .orElseGet(() -> {
                    Department d = new Department();
                    d.setName("Operations");
                    return departmentRepository.save(d);
                });

        JobRole hrSpecialist = jobRoleRepository.findByTitleIgnoreCaseAndDepartmentId("HR Specialist", hrDept.getId())
                .orElseGet(() -> {
                    JobRole jr = new JobRole();
                    jr.setTitle("HR Specialist");
                    jr.setLevel(3);
                    jr.setDepartment(hrDept);
                    return jobRoleRepository.save(jr);
                });

        JobRole sweRole = jobRoleRepository.findByTitleIgnoreCaseAndDepartmentId("Software Engineer", engDept.getId())
                .orElseGet(() -> {
                    JobRole jr = new JobRole();
                    jr.setTitle("Software Engineer");
                    jr.setLevel(2);
                    jr.setDepartment(engDept);
                    return jobRoleRepository.save(jr);
                });

        jobRoleRepository.findByTitleIgnoreCaseAndDepartmentId("Operations Analyst", opsDept.getId())
                .orElseGet(() -> {
                    JobRole jr = new JobRole();
                    jr.setTitle("Operations Analyst");
                    jr.setLevel(2);
                    jr.setDepartment(opsDept);
                    return jobRoleRepository.save(jr);
                });

        // Super Admin
        if (userRepository.findByEmail("superadmin@company.com").isEmpty()) {
            User superAdmin = new User();
            superAdmin.setEmail("superadmin@company.com");
            superAdmin.setPasswordHash(encodedPassword);
            superAdmin.setRole(Role.SUPER_ADMIN);
            superAdmin.setActive(true);
            userRepository.save(superAdmin);
            log.info("Baseline SUPER_ADMIN created: superadmin@company.com");
        }

        // HR Admin (EMP-001, Priya Sharma)
        if (userRepository.findByEmail("hr1@company.com").isEmpty()) {
            User hrAdmin = new User();
            hrAdmin.setEmail("hr1@company.com");
            hrAdmin.setPasswordHash(encodedPassword);
            hrAdmin.setRole(Role.HR_ADMIN);
            hrAdmin.setHrCode("HR-001");
            hrAdmin.setActive(true);
            hrAdmin = userRepository.save(hrAdmin);

            if (employeeRepository.findByEmployeeCode("EMP-001").isEmpty()) {
                Employee hrEmp = new Employee();
                hrEmp.setUser(hrAdmin);
                hrEmp.setDepartment(hrDept);
                hrEmp.setJobRole(hrSpecialist);
                hrEmp.setEmployeeCode("EMP-001");
                hrEmp.setFullName("Priya Sharma");
                hrEmp.setPhone("+91-9876543210");
                hrEmp.setDateOfBirth(LocalDate.of(1992, 5, 14));
                hrEmp.setDateOfJoining(LocalDate.of(2023, 1, 10));
                hrEmp.setStatus(EmployeeStatus.ACTIVE);
                employeeRepository.save(hrEmp);
            }
            log.info("Baseline HR_ADMIN created: hr1@company.com (HR-001 / EMP-001)");
        }

        // Employee (EMP-002, Aarav Mehta)
        if (userRepository.findByEmail("emp1@company.com").isEmpty()) {
            User empUser = new User();
            empUser.setEmail("emp1@company.com");
            empUser.setPasswordHash(encodedPassword);
            empUser.setRole(Role.EMPLOYEE);
            empUser.setActive(true);
            empUser = userRepository.save(empUser);

            if (employeeRepository.findByEmployeeCode("EMP-002").isEmpty()) {
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
            }
            log.info("Baseline EMPLOYEE created: emp1@company.com (EMP-002)");
        }
    }

    private void seedDemoData(String encodedPassword) {
        if (userRepository.existsByEmailIgnoreCase("emp2@company.com")
                && attendanceRecordRepository != null
                && attendanceRecordRepository.count() >= 30) {
            log.info("Demo test data already exists; skipping demo seeding.");
            return;
        }

        log.info("Seeding comprehensive demo test data (departments, employees, balances, leaves, attendance, audits)...");

        // ---------------------------------------------------------------------
        // A. Extended Departments & Job Roles
        // ---------------------------------------------------------------------
        Department hrDept = departmentRepository.findByNameIgnoreCase("Human Resources").orElseThrow();
        Department engDept = departmentRepository.findByNameIgnoreCase("Engineering").orElseThrow();
        Department opsDept = departmentRepository.findByNameIgnoreCase("Operations").orElseThrow();

        Department prodDept = departmentRepository.findByNameIgnoreCase("Product & Design")
                .orElseGet(() -> {
                    Department d = new Department();
                    d.setName("Product & Design");
                    return departmentRepository.save(d);
                });

        Department mktDept = departmentRepository.findByNameIgnoreCase("Marketing & Growth")
                .orElseGet(() -> {
                    Department d = new Department();
                    d.setName("Marketing & Growth");
                    return departmentRepository.save(d);
                });

        Department finDept = departmentRepository.findByNameIgnoreCase("Finance & Accounting")
                .orElseGet(() -> {
                    Department d = new Department();
                    d.setName("Finance & Accounting");
                    return departmentRepository.save(d);
                });

        // Engineering Roles
        JobRole engManager = getOrCreateJobRole("Engineering Manager", 4, engDept);
        JobRole staffEngineer = getOrCreateJobRole("Staff Software Engineer", 4, engDept);
        JobRole seniorSwe = getOrCreateJobRole("Senior Software Engineer", 3, engDept);
        JobRole swe = getOrCreateJobRole("Software Engineer", 2, engDept);
        JobRole devops = getOrCreateJobRole("DevOps / Cloud Engineer", 3, engDept);
        JobRole qaRole = getOrCreateJobRole("QA Automation Engineer", 2, engDept);

        // HR Roles
        JobRole hrDirector = getOrCreateJobRole("HR Director", 5, hrDept);
        JobRole hrSpecialist = getOrCreateJobRole("HR Specialist", 3, hrDept);
        JobRole talentAcquisition = getOrCreateJobRole("Talent Acquisition Specialist", 2, hrDept);

        // Product & Design Roles
        JobRole prodManager = getOrCreateJobRole("Senior Product Manager", 3, prodDept);
        JobRole uiUxDesigner = getOrCreateJobRole("Lead UI/UX Designer", 3, prodDept);

        // Marketing Roles
        JobRole mktLead = getOrCreateJobRole("Growth Marketing Lead", 3, mktDept);
        JobRole contentLead = getOrCreateJobRole("Content Strategist", 2, mktDept);

        // Finance Roles
        JobRole finAnalyst = getOrCreateJobRole("Senior Financial Analyst", 3, finDept);
        JobRole accountant = getOrCreateJobRole("Staff Accountant", 2, finDept);

        // Operations Roles
        JobRole opsLead = getOrCreateJobRole("Workplace Experience Lead", 3, opsDept);
        JobRole opsAnalyst = getOrCreateJobRole("Operations Analyst", 2, opsDept);
        JobRole custOps = getOrCreateJobRole("Customer Operations Specialist", 2, opsDept);

        // ---------------------------------------------------------------------
        // B. Seed Extended Employees & User Accounts
        // ---------------------------------------------------------------------
        List<DemoEmpSpec> specs = List.of(
                new DemoEmpSpec("hr2@company.com", Role.HR_ADMIN, "HR-002", 3, "Ayaan Kulkarni", "+91-9876543202",
                        LocalDate.of(1988, 11, 4), LocalDate.of(2022, 2, 15), hrDept, hrDirector, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp2@company.com", Role.EMPLOYEE, null, 4, "Neha Kapoor", "+91-9876543203",
                        LocalDate.of(1994, 4, 18), LocalDate.of(2023, 7, 1), engDept, seniorSwe, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp3@company.com", Role.EMPLOYEE, null, 5, "Vikram Malhotra", "+91-9876543204",
                        LocalDate.of(1989, 9, 12), LocalDate.of(2022, 5, 10), engDept, engManager, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp4@company.com", Role.EMPLOYEE, null, 6, "Ananya Roy", "+91-9876543205",
                        LocalDate.of(1995, 12, 3), LocalDate.of(2023, 9, 15), prodDept, uiUxDesigner, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp5@company.com", Role.EMPLOYEE, null, 7, "Rohan Verma", "+91-9876543206",
                        LocalDate.of(1996, 3, 27), LocalDate.of(2024, 1, 10), engDept, devops, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp6@company.com", Role.EMPLOYEE, null, 8, "Pooja Patel", "+91-9876543207",
                        LocalDate.of(1991, 6, 19), LocalDate.of(2022, 10, 1), prodDept, prodManager, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp7@company.com", Role.EMPLOYEE, null, 9, "Karan Joshi", "+91-9876543208",
                        LocalDate.of(1998, 1, 15), LocalDate.of(2024, 4, 15), engDept, qaRole, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp8@company.com", Role.EMPLOYEE, null, 10, "Sneha Iyer", "+91-9876543209",
                        LocalDate.of(1993, 7, 28), LocalDate.of(2023, 3, 20), finDept, finAnalyst, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp9@company.com", Role.EMPLOYEE, null, 11, "Arjun Nair", "+91-9876543212",
                        LocalDate.of(1992, 10, 9), LocalDate.of(2023, 6, 12), mktDept, mktLead, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp10@company.com", Role.EMPLOYEE, null, 12, "Meera Reddy", "+91-9876543213",
                        LocalDate.of(1996, 8, 14), LocalDate.of(2024, 2, 1), hrDept, talentAcquisition, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp11@company.com", Role.EMPLOYEE, null, 13, "Aditya Rao", "+91-9876543214",
                        LocalDate.of(1990, 2, 25), LocalDate.of(2022, 8, 1), engDept, staffEngineer, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp12@company.com", Role.EMPLOYEE, null, 14, "Ishaan Gupta", "+91-9876543215",
                        LocalDate.of(1997, 5, 30), LocalDate.of(2024, 5, 2), opsDept, opsAnalyst, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp13@company.com", Role.EMPLOYEE, null, 15, "Kavya Sen", "+91-9876543216",
                        LocalDate.of(1995, 9, 17), LocalDate.of(2023, 11, 20), opsDept, custOps, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp14@company.com", Role.EMPLOYEE, null, 16, "Rahul Bose", "+91-9876543217",
                        LocalDate.of(1994, 11, 11), LocalDate.of(2023, 8, 1), finDept, accountant, EmployeeStatus.ACTIVE),
                new DemoEmpSpec("emp15@company.com", Role.EMPLOYEE, null, 17, "Tanvi Deshmukh", "+91-9876543218",
                        LocalDate.of(1997, 2, 14), LocalDate.of(2024, 3, 15), mktDept, contentLead, EmployeeStatus.ON_LEAVE),
                new DemoEmpSpec("emp16@company.com", Role.EMPLOYEE, null, 18, "Siddharth Mukherjee", "+91-9876543219",
                        LocalDate.of(1991, 8, 8), LocalDate.of(2023, 4, 10), opsDept, opsLead, EmployeeStatus.ACTIVE)
        );

        Map<String, Employee> createdEmployees = new HashMap<>();
        // Index existing baseline employees
        employeeRepository.findByEmployeeCode("EMP-001").ifPresent(e -> createdEmployees.put("hr1@company.com", e));
        employeeRepository.findByEmployeeCode("EMP-002").ifPresent(e -> createdEmployees.put("emp1@company.com", e));

        for (DemoEmpSpec s : specs) {
            User u = userRepository.findByEmail(s.email).orElseGet(() -> {
                User newUser = new User();
                newUser.setEmail(s.email);
                newUser.setPasswordHash(encodedPassword);
                newUser.setRole(s.systemRole);
                if (s.systemRole == Role.HR_ADMIN) {
                    newUser.setHrCode(s.hrCode != null ? s.hrCode : allocateHrCode(2));
                }
                newUser.setActive(true);
                return userRepository.save(newUser);
            });

            final User linkedUser = u;
            Employee emp = employeeRepository.findByUserId(linkedUser.getId()).orElseGet(() -> {
                String code = allocateEmployeeCode(s.preferredCodeNum);
                Employee newEmp = new Employee();
                newEmp.setUser(linkedUser);
                newEmp.setEmployeeCode(code);
                newEmp.setFullName(s.fullName);
                newEmp.setPhone(s.phone);
                newEmp.setDateOfBirth(s.dob);
                newEmp.setDateOfJoining(s.doj);
                newEmp.setDepartment(s.dept);
                newEmp.setJobRole(s.jobRole);
                newEmp.setStatus(s.status);
                return employeeRepository.save(newEmp);
            });

            createdEmployees.put(s.email, emp);
        }

        // ---------------------------------------------------------------------
        // C. Configure Manager Reporting Lines & Department Heads
        // ---------------------------------------------------------------------
        Employee vikramEngMgr = createdEmployees.get("emp3@company.com");
        Employee ayaanHrDir = createdEmployees.get("hr2@company.com");
        Employee poojaProdMgr = createdEmployees.get("emp6@company.com");
        Employee snehaFinLead = createdEmployees.get("emp8@company.com");
        Employee arjunMktLead = createdEmployees.get("emp9@company.com");
        Employee siddharthOpsLead = createdEmployees.get("emp16@company.com");

        if (vikramEngMgr != null) {
            setManagerIfPresent(createdEmployees.get("emp1@company.com"), vikramEngMgr);
            setManagerIfPresent(createdEmployees.get("emp2@company.com"), vikramEngMgr);
            setManagerIfPresent(createdEmployees.get("emp5@company.com"), vikramEngMgr);
            setManagerIfPresent(createdEmployees.get("emp7@company.com"), vikramEngMgr);
            setManagerIfPresent(createdEmployees.get("emp11@company.com"), vikramEngMgr);
            engDept.setHeadEmployee(vikramEngMgr);
            departmentRepository.save(engDept);
        }

        if (ayaanHrDir != null) {
            setManagerIfPresent(createdEmployees.get("hr1@company.com"), ayaanHrDir);
            setManagerIfPresent(createdEmployees.get("emp10@company.com"), ayaanHrDir);
            hrDept.setHeadEmployee(ayaanHrDir);
            departmentRepository.save(hrDept);
        }

        if (poojaProdMgr != null) {
            setManagerIfPresent(createdEmployees.get("emp4@company.com"), poojaProdMgr);
            prodDept.setHeadEmployee(poojaProdMgr);
            departmentRepository.save(prodDept);
        }

        if (snehaFinLead != null) {
            setManagerIfPresent(createdEmployees.get("emp14@company.com"), snehaFinLead);
            finDept.setHeadEmployee(snehaFinLead);
            departmentRepository.save(finDept);
        }

        if (arjunMktLead != null) {
            setManagerIfPresent(createdEmployees.get("emp15@company.com"), arjunMktLead);
            mktDept.setHeadEmployee(arjunMktLead);
            departmentRepository.save(mktDept);
        }

        if (siddharthOpsLead != null) {
            setManagerIfPresent(createdEmployees.get("emp12@company.com"), siddharthOpsLead);
            setManagerIfPresent(createdEmployees.get("emp13@company.com"), siddharthOpsLead);
            opsDept.setHeadEmployee(siddharthOpsLead);
            departmentRepository.save(opsDept);
        }

        // ---------------------------------------------------------------------
        // D. Initialize Leave Balances for 2026
        // ---------------------------------------------------------------------
        int currentYear = LocalDate.now(clock).getYear();
        List<LeaveType> leaveTypes = leaveTypeRepository.findAll();
        for (Employee emp : employeeRepository.findAll()) {
            for (LeaveType lt : leaveTypes) {
                if (leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(emp.getId(), lt.getId(), currentYear).isEmpty()) {
                    BigDecimal alloc = lt.getDefaultDaysPerYear() != null ? lt.getDefaultDaysPerYear() : BigDecimal.ZERO;
                    LeaveBalance lb = new LeaveBalance(emp, lt, currentYear, alloc);
                    leaveBalanceRepository.save(lb);
                }
            }
        }

        // ---------------------------------------------------------------------
        // E. Seed Diverse Leave Requests (Pending, Approved, Rejected)
        // ---------------------------------------------------------------------
        LeaveType annualType = leaveTypeRepository.findByCode("ANNUAL").orElse(null);
        LeaveType sickType = leaveTypeRepository.findByCode("SICK").orElse(null);
        LeaveType casualType = leaveTypeRepository.findByCode("CASUAL").orElse(null);
        User hr1User = userRepository.findByEmail("hr1@company.com").orElse(null);
        User hr2User = userRepository.findByEmail("hr2@company.com").orElse(null);

        if (annualType != null && sickType != null && casualType != null && leaveRequestRepository.count() == 0) {
            LocalDate today = LocalDate.now(clock);

            // 1. Pending: Neha Kapoor (emp2) - 3 days Annual next week
            Employee neha = createdEmployees.get("emp2@company.com");
            if (neha != null) {
                LocalDate start = today.plusDays(5);
                LocalDate end = today.plusDays(7);
                createLeaveRequest(neha, annualType, start, end, new BigDecimal("3.0"),
                        "Attending cousin's wedding ceremony in Jaipur.", LeaveStatus.PENDING, null, null, null);
                holdPendingBalance(neha, annualType, currentYear, new BigDecimal("3.0"));
            }

            // 2. Pending: Rohan Verma (emp5) - 1 day Casual
            Employee rohan = createdEmployees.get("emp5@company.com");
            if (rohan != null) {
                LocalDate start = today.plusDays(8);
                createLeaveRequest(rohan, casualType, start, start, new BigDecimal("1.0"),
                        "Urgent personal paperwork at city registrar office.", LeaveStatus.PENDING, null, null, null);
                holdPendingBalance(rohan, casualType, currentYear, new BigDecimal("1.0"));
            }

            // 3. Pending: Sneha Iyer (emp8) - 2 days Sick
            Employee sneha = createdEmployees.get("emp8@company.com");
            if (sneha != null) {
                LocalDate start = today.plusDays(6);
                LocalDate end = today.plusDays(7);
                createLeaveRequest(sneha, sickType, start, end, new BigDecimal("2.0"),
                        "Scheduled minor outpatient procedure and rest.", LeaveStatus.PENDING, null, null, null);
                holdPendingBalance(sneha, sickType, currentYear, new BigDecimal("2.0"));
            }

            // 4. Pending: Ananya Roy (emp4) - 3 days Annual in December
            Employee ananya = createdEmployees.get("emp4@company.com");
            if (ananya != null) {
                LocalDate start = LocalDate.of(currentYear, 12, 7);
                LocalDate end = LocalDate.of(currentYear, 12, 9);
                createLeaveRequest(ananya, annualType, start, end, new BigDecimal("3.0"),
                        "Annual winter vacation with family.", LeaveStatus.PENDING, null, null, null);
                holdPendingBalance(ananya, annualType, currentYear, new BigDecimal("3.0"));
            }

            // 5. Approved: Pooja Patel (emp6) - 3 days Annual in October
            Employee pooja = createdEmployees.get("emp6@company.com");
            if (pooja != null && hr1User != null) {
                LocalDate start = LocalDate.of(currentYear, 10, 12);
                LocalDate end = LocalDate.of(currentYear, 10, 14);
                createLeaveRequest(pooja, annualType, start, end, new BigDecimal("3.0"),
                        "Pre-Diwali family travel and home celebration.", LeaveStatus.APPROVED, hr1User,
                        OffsetDateTime.of(currentYear, 10, 8, 11, 30, 0, 0, ZoneOffset.UTC), null);
                consumeApprovedBalance(pooja, annualType, currentYear, new BigDecimal("3.0"));
            }

            // 6. Approved: Priya Sharma (hr1) - 2 days Casual in September
            Employee priya = createdEmployees.get("hr1@company.com");
            if (priya != null && hr2User != null) {
                LocalDate start = LocalDate.of(currentYear, 9, 17);
                LocalDate end = LocalDate.of(currentYear, 9, 18);
                createLeaveRequest(priya, casualType, start, end, new BigDecimal("2.0"),
                        "Home relocation and lease documentation.", LeaveStatus.APPROVED, hr2User,
                        OffsetDateTime.of(currentYear, 9, 14, 9, 15, 0, 0, ZoneOffset.UTC), null);
                consumeApprovedBalance(priya, casualType, currentYear, new BigDecimal("2.0"));
            }

            // 7. Approved: Vikram Malhotra (emp3) - 3 days Sick in August
            Employee vikram = createdEmployees.get("emp3@company.com");
            if (vikram != null && hr1User != null) {
                LocalDate start = LocalDate.of(currentYear, 8, 5);
                LocalDate end = LocalDate.of(currentYear, 8, 7);
                createLeaveRequest(vikram, sickType, start, end, new BigDecimal("3.0"),
                        "Seasonal viral fever recovery with physician prescription.", LeaveStatus.APPROVED, hr1User,
                        OffsetDateTime.of(currentYear, 8, 5, 8, 45, 0, 0, ZoneOffset.UTC), null);
                consumeApprovedBalance(vikram, sickType, currentYear, new BigDecimal("3.0"));
            }

            // 8. Approved: Karan Joshi (emp7) - 5 days Annual in July
            Employee karan = createdEmployees.get("emp7@company.com");
            if (karan != null && hr1User != null) {
                LocalDate start = LocalDate.of(currentYear, 7, 20);
                LocalDate end = LocalDate.of(currentYear, 7, 24);
                createLeaveRequest(karan, annualType, start, end, new BigDecimal("5.0"),
                        "Monsoon trekking expedition in Himachal.", LeaveStatus.APPROVED, hr1User,
                        OffsetDateTime.of(currentYear, 7, 15, 14, 20, 0, 0, ZoneOffset.UTC), null);
                consumeApprovedBalance(karan, annualType, currentYear, new BigDecimal("5.0"));
            }

            // 9. Approved (Current Week): Tanvi Deshmukh (emp15) - 5 days Annual this week
            Employee tanvi = createdEmployees.get("emp15@company.com");
            if (tanvi != null && hr1User != null) {
                LocalDate mondayThisWeek = today.minusDays(today.getDayOfWeek().getValue() - 1);
                LocalDate fridayThisWeek = mondayThisWeek.plusDays(4);
                createLeaveRequest(tanvi, annualType, mondayThisWeek, fridayThisWeek, new BigDecimal("5.0"),
                        "Annual wellness retreat.", LeaveStatus.APPROVED, hr1User,
                        OffsetDateTime.of(mondayThisWeek.minusDays(3), LocalTime.of(16, 0), ZoneOffset.UTC), null);
                consumeApprovedBalance(tanvi, annualType, currentYear, new BigDecimal("5.0"));
            }

            // 10. Rejected: Rohan Verma (emp5)
            if (rohan != null && hr1User != null) {
                LocalDate start = LocalDate.of(currentYear, 6, 1);
                LocalDate end = LocalDate.of(currentYear, 6, 5);
                createLeaveRequest(rohan, annualType, start, end, new BigDecimal("5.0"),
                        "Early summer travel.", LeaveStatus.REJECTED, hr1User,
                        OffsetDateTime.of(currentYear, 5, 28, 10, 0, 0, 0, ZoneOffset.UTC),
                        "Critical Q2 production database migration sprint scheduled during this window. Please re-apply after freeze.");
            }

            // 11. Rejected: Rahul Bose (emp14)
            Employee rahul = createdEmployees.get("emp14@company.com");
            if (rahul != null && hr1User != null) {
                LocalDate start = LocalDate.of(currentYear, 3, 30);
                LocalDate end = LocalDate.of(currentYear, 3, 31);
                createLeaveRequest(rahul, casualType, start, end, new BigDecimal("2.0"),
                        "Personal city trip.", LeaveStatus.REJECTED, hr1User,
                        OffsetDateTime.of(currentYear, 3, 25, 11, 0, 0, 0, ZoneOffset.UTC),
                        "Fiscal year-end closing window. All finance team members required on-site.");
            }
        }

        // ---------------------------------------------------------------------
        // F. Seed 14-Day Attendance Trends & Today's Active Punches
        // ---------------------------------------------------------------------
        if (attendanceRecordRepository != null) {
            seedAttendanceHistory(createdEmployees);
        }

        // ---------------------------------------------------------------------
        // G. Seed Realistic Audit Trail Logs
        // ---------------------------------------------------------------------
        if (auditLogRepository != null && auditLogRepository.count() < 5) {
            seedAuditLogs(createdEmployees);
        }

        // ---------------------------------------------------------------------
        // H. Resynchronize Database Sequences
        // ---------------------------------------------------------------------
        syncSequences();

        log.info("Demo test data seeding complete! Database populated with 18 employees, live charts, leaves & attendance.");
    }

    private void seedAttendanceHistory(Map<String, Employee> employees) {
        LocalDate today = LocalDate.now(clock);
        List<Employee> allEmployees = employeeRepository.findAll();

        for (int dayOffset = 13; dayOffset >= 0; dayOffset--) {
            LocalDate workDate = today.minusDays(dayOffset);
            DayOfWeek dow = workDate.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                continue; // Skip weekends
            }

            for (Employee emp : allEmployees) {
                if (emp.getStatus() == EmployeeStatus.INACTIVE) {
                    continue;
                }
                if (attendanceRecordRepository.findByEmployeeIdAndWorkDate(emp.getId(), workDate).isPresent()) {
                    continue;
                }

                // Preserve clean test state for emp1 to prevent collision with unit/integration test suites
                String empEmail = (emp.getUser() != null) ? emp.getUser().getEmail() : "";
                if ("emp1@company.com".equals(empEmail)) {
                    continue;
                }

                // Check if employee is on approved leave today/this week
                if ("emp15@company.com".equals(emp.getUser() != null ? emp.getUser().getEmail() : "")
                        && !workDate.isBefore(today.minusDays(today.getDayOfWeek().getValue() - 1))
                        && !workDate.isAfter(today.minusDays(today.getDayOfWeek().getValue() - 1).plusDays(4))) {
                    AttendanceRecord rec = new AttendanceRecord(emp, workDate, AttendanceStatus.ON_LEAVE, WorkMode.ON_SITE);
                    rec.setNotes("Approved Leave: Annual / Privilege Leave");
                    attendanceRecordRepository.save(rec);
                    continue;
                }

                if (workDate.equals(today)) {
                    // Seed Today's live punches
                    seedTodayPunch(emp, today);
                } else {
                    // Seed Historical punch (deterministic based on employee + date)
                    seedHistoricalPunch(emp, workDate);
                }
            }
        }
    }

    private void seedTodayPunch(Employee emp, LocalDate today) {
        String email = emp.getUser() != null ? emp.getUser().getEmail() : "";
        ZoneOffset istOffset = ZoneOffset.ofHoursMinutes(5, 30);

        if ("hr1@company.com".equals(email)) {
            // Priya Sharma (HR Admin): Clocked in at 09:02 AM ON_SITE
            AttendanceRecord rec = new AttendanceRecord(emp, today, AttendanceStatus.PRESENT, WorkMode.ON_SITE);
            rec.setClockIn(OffsetDateTime.of(today, LocalTime.of(9, 2, 0), istOffset));
            rec.setClockInIp("192.168.1.10");
            rec.setNotes("Morning on-site HR desk");
            attendanceRecordRepository.save(rec);
        } else if ("emp2@company.com".equals(email)) {
            // Neha Kapoor: Clocked in at 09:14 AM ON_SITE
            AttendanceRecord rec = new AttendanceRecord(emp, today, AttendanceStatus.PRESENT, WorkMode.ON_SITE);
            rec.setClockIn(OffsetDateTime.of(today, LocalTime.of(9, 14, 0), istOffset));
            rec.setClockInIp("192.168.1.12");
            attendanceRecordRepository.save(rec);
        } else if ("emp7@company.com".equals(email)) {
            // Karan Joshi: Clocked in LATE at 10:25 AM
            AttendanceRecord rec = new AttendanceRecord(emp, today, AttendanceStatus.LATE, WorkMode.ON_SITE);
            rec.setClockIn(OffsetDateTime.of(today, LocalTime.of(10, 25, 0), istOffset));
            rec.setClockInIp("192.168.1.18");
            rec.setNotes("Metro delay on purple line");
            attendanceRecordRepository.save(rec);
        } else {
            int empHash = Math.abs((emp.getFullName() != null ? emp.getFullName().hashCode() : 1) + today.getDayOfYear());
            if (empHash % 4 == 0) {
                // Not clocked in yet (gives variety for the dashboard)
                return;
            }

            WorkMode mode = (empHash % 3 == 0) ? WorkMode.REMOTE : WorkMode.ON_SITE;
            int minute = 50 + (empHash % 25);
            int inHour = (minute >= 60) ? 9 : 8;
            int inMin = minute % 60;

            AttendanceRecord rec = new AttendanceRecord(emp, today, AttendanceStatus.PRESENT, mode);
            rec.setClockIn(OffsetDateTime.of(today, LocalTime.of(inHour, inMin, 0), istOffset));
            rec.setClockInIp(mode == WorkMode.REMOTE ? "49.37.104.22" : "192.168.1.45");
            attendanceRecordRepository.save(rec);
        }
    }

    private void seedHistoricalPunch(Employee emp, LocalDate workDate) {
        ZoneOffset istOffset = ZoneOffset.ofHoursMinutes(5, 30);
        int codeHash = emp.getEmployeeCode() != null ? emp.getEmployeeCode().hashCode() : 1;
        int seed = Math.abs(codeHash * 31 + workDate.getDayOfYear()) % 100;

        AttendanceRecord rec;
        if (seed < 80) {
            // 80% PRESENT
            WorkMode mode = (seed % 4 == 0) ? WorkMode.REMOTE : WorkMode.ON_SITE;
            int inMin = 50 + (seed % 22);
            int inHour = (inMin >= 60) ? 9 : 8;
            inMin = inMin % 60;
            int outMin = 5 + (seed % 30);

            OffsetDateTime clockIn = OffsetDateTime.of(workDate, LocalTime.of(inHour, inMin, 0), istOffset);
            OffsetDateTime clockOut = OffsetDateTime.of(workDate, LocalTime.of(18, outMin, 0), istOffset);
            int duration = (int) Duration.between(clockIn, clockOut).toMinutes();

            rec = new AttendanceRecord(emp, workDate, AttendanceStatus.PRESENT, mode);
            rec.setClockIn(clockIn);
            rec.setClockOut(clockOut);
            rec.setDurationMinutes(duration);
            rec.setClockInIp(mode == WorkMode.REMOTE ? "49.37.104.22" : "192.168.1.15");
            rec.setClockOutIp(rec.getClockInIp());
        } else if (seed < 90) {
            // 10% LATE
            int inMin = 15 + (seed % 30);
            OffsetDateTime clockIn = OffsetDateTime.of(workDate, LocalTime.of(10, inMin, 0), istOffset);
            OffsetDateTime clockOut = OffsetDateTime.of(workDate, LocalTime.of(18, 30, 0), istOffset);
            int duration = (int) Duration.between(clockIn, clockOut).toMinutes();

            rec = new AttendanceRecord(emp, workDate, AttendanceStatus.LATE, WorkMode.ON_SITE);
            rec.setClockIn(clockIn);
            rec.setClockOut(clockOut);
            rec.setDurationMinutes(duration);
            rec.setNotes("Traffic congestion during morning rush hour");
            rec.setClockInIp("192.168.1.20");
            rec.setClockOutIp("192.168.1.20");
        } else if (seed < 95) {
            // 5% HALF_DAY
            OffsetDateTime clockIn = OffsetDateTime.of(workDate, LocalTime.of(9, 10, 0), istOffset);
            OffsetDateTime clockOut = OffsetDateTime.of(workDate, LocalTime.of(13, 40, 0), istOffset);
            int duration = (int) Duration.between(clockIn, clockOut).toMinutes();

            rec = new AttendanceRecord(emp, workDate, AttendanceStatus.HALF_DAY, WorkMode.ON_SITE);
            rec.setClockIn(clockIn);
            rec.setClockOut(clockOut);
            rec.setDurationMinutes(duration);
            rec.setNotes("First half worked, second half permitted leave");
            rec.setClockInIp("192.168.1.25");
            rec.setClockOutIp("192.168.1.25");
        } else {
            // 5% ABSENT
            rec = new AttendanceRecord(emp, workDate, AttendanceStatus.ABSENT, WorkMode.ON_SITE);
            rec.setDurationMinutes(0);
            rec.setNotes("Unscheduled absence");
        }

        attendanceRecordRepository.save(rec);
    }

    private void seedAuditLogs(Map<String, Employee> employees) {
        User superAdmin = userRepository.findByEmail("superadmin@company.com").orElse(null);
        User hr1 = userRepository.findByEmail("hr1@company.com").orElse(null);
        if (superAdmin == null && hr1 == null) return;

        User primaryActor = hr1 != null ? hr1 : superAdmin;
        Instant now = Instant.now();

        List<AuditEntrySpec> auditEntries = List.of(
                new AuditEntrySpec(primaryActor, "HR-001", "SYSTEM_BOOTSTRAPPED", "SystemConfig",
                        UUID.randomUUID(), null, Map.of("version", "2.0.0", "status", "ONLINE", "cluster", "production"),
                        "127.0.0.1", now.minus(Duration.ofDays(14))),
                new AuditEntrySpec(primaryActor, "HR-001", "EMPLOYEE_CREATED", "Employee",
                        UUID.randomUUID(), null, Map.of("employeeCode", "EMP-003", "fullName", "Ayaan Kulkarni", "role", "HR_ADMIN"),
                        "192.168.1.10", now.minus(Duration.ofDays(10))),
                new AuditEntrySpec(primaryActor, "HR-001", "EMPLOYEE_CREATED", "Employee",
                        UUID.randomUUID(), null, Map.of("employeeCode", "EMP-004", "fullName", "Neha Kapoor", "department", "Engineering"),
                        "192.168.1.10", now.minus(Duration.ofDays(8))),
                new AuditEntrySpec(primaryActor, "HR-001", "EMPLOYEE_CREATED", "Employee",
                        UUID.randomUUID(), null, Map.of("employeeCode", "EMP-005", "fullName", "Vikram Malhotra", "jobRole", "Engineering Manager"),
                        "192.168.1.10", now.minus(Duration.ofDays(7))),
                new AuditEntrySpec(primaryActor, "HR-001", "LEAVE_REQUESTED", "LeaveRequest",
                        UUID.randomUUID(), null, Map.of("employee", "Aarav Mehta", "leaveType", "ANNUAL", "days", 3.0),
                        "192.168.1.12", now.minus(Duration.ofDays(5))),
                new AuditEntrySpec(primaryActor, "HR-001", "APPROVE_LEAVE", "LeaveRequest",
                        UUID.randomUUID(), Map.of("status", "PENDING"), Map.of("status", "APPROVED", "actionedBy", "hr1@company.com"),
                        "192.168.1.10", now.minus(Duration.ofDays(4))),
                new AuditEntrySpec(primaryActor, "HR-001", "ATTENDANCE_CORRECTED", "AttendanceRecord",
                        UUID.randomUUID(), Map.of("status", "LATE"), Map.of("status", "PRESENT", "reason", "Client transit verified"),
                        "192.168.1.10", now.minus(Duration.ofDays(2)))
        );

        for (AuditEntrySpec spec : auditEntries) {
            AuditLog entry = new AuditLog();
            entry.setId(UUID.randomUUID());
            entry.setSessionId("seed-session-" + UUID.randomUUID().toString().substring(0, 8));
            entry.setUser(spec.user);
            entry.setHrCode(spec.hrCode);
            entry.setAction(spec.action);
            entry.setTargetType(spec.targetType);
            entry.setTargetId(spec.targetId);
            entry.setPreviousData(spec.previousData);
            entry.setNewData(spec.newData);
            entry.setIpAddress(spec.ipAddress);
            entry.setOccurredAt(spec.occurredAt);
            auditLogRepository.save(entry);
        }
    }

    private void syncSequences() {
        if (jdbcTemplate == null) return;
        try {
            jdbcTemplate.execute(
                    "SELECT setval('seq_employee_code', GREATEST(COALESCE((SELECT MAX(CAST(SUBSTRING(employee_code, 5) AS BIGINT)) FROM employees WHERE employee_code ~ '^EMP-[0-9]+$'), 3), 3), true)");
            jdbcTemplate.execute(
                    "SELECT setval('seq_hr_code', GREATEST(COALESCE((SELECT MAX(CAST(SUBSTRING(hr_code, 4) AS BIGINT)) FROM users WHERE hr_code ~ '^HR-[0-9]+$'), 2), 2), true)");
            log.info("PostgreSQL sequences seq_employee_code and seq_hr_code resynchronized.");
        } catch (Exception ex) {
            log.warn("Could not resynchronize sequences: {}", ex.getMessage());
        }
    }

    private String allocateEmployeeCode(int preferred) {
        int cur = preferred;
        while (employeeRepository.existsByEmployeeCode(String.format("EMP-%03d", cur))) {
            cur++;
        }
        return String.format("EMP-%03d", cur);
    }

    private String allocateHrCode(int preferred) {
        int cur = preferred;
        while (userRepository.findByHrCode(String.format("HR-%03d", cur)).isPresent()) {
            cur++;
        }
        return String.format("HR-%03d", cur);
    }

    private JobRole getOrCreateJobRole(String title, int level, Department dept) {
        return jobRoleRepository.findByTitleIgnoreCaseAndDepartmentId(title, dept.getId())
                .orElseGet(() -> {
                    JobRole jr = new JobRole();
                    jr.setTitle(title);
                    jr.setLevel(level);
                    jr.setDepartment(dept);
                    return jobRoleRepository.save(jr);
                });
    }

    private void setManagerIfPresent(Employee subordinate, Employee manager) {
        if (subordinate != null && manager != null && !subordinate.getId().equals(manager.getId())) {
            subordinate.setReportsTo(manager);
            employeeRepository.save(subordinate);
        }
    }

    private void createLeaveRequest(Employee employee, LeaveType leaveType, LocalDate start, LocalDate end,
                                    BigDecimal totalDays, String reason, LeaveStatus status, User actionedBy,
                                    OffsetDateTime actionedAt, String rejectionReason) {
        LeaveRequest req = new LeaveRequest(employee, leaveType, start, end, totalDays, reason);
        req.setStatus(status);
        req.setActionedBy(actionedBy);
        req.setActionedAt(actionedAt);
        req.setRejectionReason(rejectionReason);
        leaveRequestRepository.save(req);
    }

    private void holdPendingBalance(Employee employee, LeaveType leaveType, int year, BigDecimal days) {
        leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(employee.getId(), leaveType.getId(), year)
                .ifPresent(b -> {
                    b.setPendingDays(b.getPendingDays().add(days));
                    b.setRemainingDays(b.getRemainingDays().subtract(days));
                    leaveBalanceRepository.save(b);
                });
    }

    private void consumeApprovedBalance(Employee employee, LeaveType leaveType, int year, BigDecimal days) {
        leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(employee.getId(), leaveType.getId(), year)
                .ifPresent(b -> {
                    b.setUsedDays(b.getUsedDays().add(days));
                    b.setRemainingDays(b.getRemainingDays().subtract(days));
                    leaveBalanceRepository.save(b);
                });
    }

    private record DemoEmpSpec(String email, Role systemRole, String hrCode, int preferredCodeNum,
                               String fullName, String phone, LocalDate dob, LocalDate doj,
                               Department dept, JobRole jobRole, EmployeeStatus status) {
    }

    private record AuditEntrySpec(User user, String hrCode, String action, String targetType,
                                  UUID targetId, Map<String, Object> previousData, Map<String, Object> newData,
                                  String ipAddress, Instant occurredAt) {
    }
}
