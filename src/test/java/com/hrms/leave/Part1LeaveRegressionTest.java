package com.hrms.leave;

import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.attendance.entity.Holiday;
import com.hrms.attendance.entity.WorkMode;
import com.hrms.attendance.repository.AttendanceRecordRepository;
import com.hrms.attendance.repository.HolidayRepository;
import com.hrms.audit.entity.AuditLog;
import com.hrms.audit.repository.AuditLogRepository;
import com.hrms.department.entity.Department;
import com.hrms.department.entity.JobRole;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.department.repository.JobRoleRepository;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.exception.BusinessException;
import com.hrms.leave.dto.LeaveApplicationDto;
import com.hrms.leave.entity.LeaveBalance;
import com.hrms.leave.entity.LeaveRequest;
import com.hrms.leave.entity.LeaveStatus;
import com.hrms.leave.entity.LeaveType;
import com.hrms.leave.repository.LeaveBalanceRepository;
import com.hrms.leave.repository.LeaveRequestRepository;
import com.hrms.leave.repository.LeaveTypeRepository;
import com.hrms.leave.service.LeaveBalanceService;
import com.hrms.leave.service.LeaveService;
import com.hrms.notification.service.NotificationService;
import com.hrms.security.CustomUserDetails;
import com.hrms.security.CustomUserDetailsService;
import com.hrms.security.entity.Role;
import com.hrms.security.entity.User;
import com.hrms.security.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration & regression tests for:
 * - BUG-06: Structured @Audited before/after JSONB snapshots on leave actions
 * - BUG-07: Public holiday exclusions (exact + recurring), cross-year balance holds, and existing punch protection
 * - BUG-08: Post-commit async email dispatch (AFTER_COMMIT) avoiding false emails on rollback
 */
@SpringBootTest
class Part1LeaveRegressionTest {

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private LeaveBalanceService leaveBalanceService;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private LeaveBalanceRepository leaveBalanceRepository;

    @Autowired
    private LeaveTypeRepository leaveTypeRepository;

    @Autowired
    private AttendanceRecordRepository attendanceRecordRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private JobRoleRepository jobRoleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @MockitoSpyBean
    private NotificationService notificationService;

    @Test
    @DisplayName("BUG-07: Holidays excluded from leave days & attendance sync preserves punched records")
    void leaveExcludesHolidaysAndPreservesPunchedAttendance() {
        TempEmpContext tmp = createTempEmployee("leave.hol");
        Holiday tempHoliday = null;
        try {
            LeaveType annualType = leaveTypeRepository.findByCode("ANNUAL").orElseThrow();
            LocalDate nextMonday = nextUpcomingMonday(LocalDate.now(clock).plusWeeks(2));
            LocalDate nextTuesday = nextMonday.plusDays(1);
            LocalDate nextWednesday = nextMonday.plusDays(2);

            // Ensure clean holiday slot on Tuesday
            if (!holidayRepository.isPublicHoliday(nextTuesday)) {
                tempHoliday = holidayRepository.save(
                        new Holiday("Test Midweek Holiday", nextTuesday, false, "Regression test holiday"));
            }

            // Pre-existing punched AttendanceRecord on Wednesday
            AttendanceRecord existingPunch = new AttendanceRecord(
                    tmp.employee(), nextWednesday, AttendanceStatus.PRESENT, WorkMode.ON_SITE);
            existingPunch.setClockIn(OffsetDateTime.now(clock).minusHours(2));
            existingPunch.setNotes("Existing punch must not be overwritten");
            attendanceRecordRepository.saveAndFlush(existingPunch);

            // Range containing ONLY public holiday (or weekend) is rejected
            if (tempHoliday != null) {
                LeaveApplicationDto holidayOnlyDto = new LeaveApplicationDto();
                holidayOnlyDto.setLeaveTypeId(annualType.getId());
                holidayOnlyDto.setStartDate(nextTuesday);
                holidayOnlyDto.setEndDate(nextTuesday);
                holidayOnlyDto.setReason("Holiday only check");
                assertThrows(BusinessException.class,
                        () -> leaveService.applyForLeave(tmp.user().getId(), holidayOnlyDto));
            }

            // Apply Mon-Wed (3 calendar days -> 2 working days if Tuesday is holiday)
            LeaveApplicationDto dto = new LeaveApplicationDto();
            dto.setLeaveTypeId(annualType.getId());
            dto.setStartDate(nextMonday);
            dto.setEndDate(nextWednesday);
            dto.setReason("Three day span around public holiday");

            LeaveRequest applied = leaveService.applyForLeave(tmp.user().getId(), dto);
            assertEquals(0, new BigDecimal("2.0").compareTo(applied.getTotalDays()),
                    "Tuesday public holiday must be excluded from totalDays");

            User hrUser = userRepository.findByEmail("hr1@company.com").orElseThrow();
            leaveService.approveLeave(applied.getId(), hrUser.getId());

            AttendanceRecord monRec = attendanceRecordRepository
                    .findByEmployeeIdAndWorkDate(tmp.employee().getId(), nextMonday).orElseThrow();
            assertEquals(AttendanceStatus.ON_LEAVE, monRec.getStatus());

            assertTrue(attendanceRecordRepository.findByEmployeeIdAndWorkDate(tmp.employee().getId(), nextTuesday).isEmpty(),
                    "Public holiday must NOT get an ON_LEAVE attendance record");

            AttendanceRecord wedRec = attendanceRecordRepository
                    .findByEmployeeIdAndWorkDate(tmp.employee().getId(), nextWednesday).orElseThrow();
            assertEquals(AttendanceStatus.PRESENT, wedRec.getStatus(),
                    "Existing clocked-in AttendanceRecord must be preserved instead of overwritten to ON_LEAVE");
        } finally {
            if (tempHoliday != null) {
                holidayRepository.delete(tempHoliday);
            }
            cleanupTempEmployee(tmp);
        }
    }

    @Test
    @DisplayName("BUG-07: Cross-year leave splits holds/approvals/releases cleanly across both calendar years")
    void crossYearLeaveSplitsAndReleasesBalancesAcrossYears() {
        TempEmpContext tmp = createTempEmployee("leave.cross");
        try {
            LeaveType annualType = leaveTypeRepository.findByCode("ANNUAL").orElseThrow();
            LocalDate today = LocalDate.now(clock);
            int baseYear = today.isAfter(LocalDate.of(today.getYear(), 12, 27))
                    ? today.getYear() + 1
                    : today.getYear();

            LocalDate start = LocalDate.of(baseYear, 12, 28);
            LocalDate end = LocalDate.of(baseYear + 1, 1, 5);

            // Compute expected working days per year independently (Jan 1 is a recurring holiday)
            BigDecimal expectedYear1 = BigDecimal.ZERO;
            BigDecimal expectedYear2 = BigDecimal.ZERO;
            LocalDate d = start;
            while (!d.isAfter(end)) {
                DayOfWeek dow = d.getDayOfWeek();
                if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidayRepository.isPublicHoliday(d)) {
                    if (d.getYear() == baseYear) {
                        expectedYear1 = expectedYear1.add(BigDecimal.ONE);
                    } else {
                        expectedYear2 = expectedYear2.add(BigDecimal.ONE);
                    }
                }
                d = d.plusDays(1);
            }
            assertTrue(expectedYear1.compareTo(BigDecimal.ZERO) > 0 && expectedYear2.compareTo(BigDecimal.ZERO) > 0);
            BigDecimal expectedTotal = expectedYear1.add(expectedYear2);

            LeaveApplicationDto dto = new LeaveApplicationDto();
            dto.setLeaveTypeId(annualType.getId());
            dto.setStartDate(start);
            dto.setEndDate(end);
            dto.setReason("Year-end cross-year leave test");

            LeaveRequest applied = leaveService.applyForLeave(tmp.user().getId(), dto);
            assertEquals(0, expectedTotal.compareTo(applied.getTotalDays()));

            LeaveBalance balY1AfterApply = leaveBalanceRepository
                    .findByEmployeeIdAndLeaveTypeIdAndYear(tmp.employee().getId(), annualType.getId(), baseYear).orElseThrow();
            LeaveBalance balY2AfterApply = leaveBalanceRepository
                    .findByEmployeeIdAndLeaveTypeIdAndYear(tmp.employee().getId(), annualType.getId(), baseYear + 1).orElseThrow();

            assertEquals(0, expectedYear1.compareTo(balY1AfterApply.getPendingDays()));
            assertEquals(0, expectedYear2.compareTo(balY2AfterApply.getPendingDays()));
            assertInvariantHolds(balY1AfterApply);
            assertInvariantHolds(balY2AfterApply);

            // Reject and verify BOTH years release pending back to remaining
            User hrUser = userRepository.findByEmail("hr1@company.com").orElseThrow();
            leaveService.rejectLeave(applied.getId(), "Critical year-end release", hrUser.getId());

            LeaveBalance balY1AfterReject = leaveBalanceRepository
                    .findByEmployeeIdAndLeaveTypeIdAndYear(tmp.employee().getId(), annualType.getId(), baseYear).orElseThrow();
            LeaveBalance balY2AfterReject = leaveBalanceRepository
                    .findByEmployeeIdAndLeaveTypeIdAndYear(tmp.employee().getId(), annualType.getId(), baseYear + 1).orElseThrow();

            assertEquals(0, BigDecimal.ZERO.compareTo(balY1AfterReject.getPendingDays()));
            assertEquals(0, BigDecimal.ZERO.compareTo(balY2AfterReject.getPendingDays()));
            assertEquals(0, balY1AfterReject.getAllocatedDays().compareTo(balY1AfterReject.getRemainingDays()));
            assertEquals(0, balY2AfterReject.getAllocatedDays().compareTo(balY2AfterReject.getRemainingDays()));

            // Re-apply and APPROVE -> verify BOTH years convert pending to used and respect Jan 1 recurring holiday
            LeaveRequest secondApplied = leaveService.applyForLeave(tmp.user().getId(), dto);
            leaveService.approveLeave(secondApplied.getId(), hrUser.getId());

            LeaveBalance balY1AfterApprove = leaveBalanceRepository
                    .findByEmployeeIdAndLeaveTypeIdAndYear(tmp.employee().getId(), annualType.getId(), baseYear).orElseThrow();
            LeaveBalance balY2AfterApprove = leaveBalanceRepository
                    .findByEmployeeIdAndLeaveTypeIdAndYear(tmp.employee().getId(), annualType.getId(), baseYear + 1).orElseThrow();

            assertEquals(0, BigDecimal.ZERO.compareTo(balY1AfterApprove.getPendingDays()));
            assertEquals(0, expectedYear1.compareTo(balY1AfterApprove.getUsedDays()));
            assertEquals(0, BigDecimal.ZERO.compareTo(balY2AfterApprove.getPendingDays()));
            assertEquals(0, expectedYear2.compareTo(balY2AfterApprove.getUsedDays()));
            assertInvariantHolds(balY1AfterApprove);
            assertInvariantHolds(balY2AfterApprove);

            // Verify Jan 1 recurring holiday got no ON_LEAVE row
            assertTrue(attendanceRecordRepository
                    .findByEmployeeIdAndWorkDate(tmp.employee().getId(), LocalDate.of(baseYear + 1, 1, 1))
                    .isEmpty());
        } finally {
            cleanupTempEmployee(tmp);
        }
    }

    @Test
    @DisplayName("BUG-08: Email notification fires AFTER_COMMIT only and is skipped on transaction rollback")
    void leaveEmailFiresOnlyAfterCommit() {
        TempEmpContext tmp = createTempEmployee("leave.event");
        try {
            reset(notificationService);
            LeaveType casualType = leaveTypeRepository.findByCode("CASUAL").orElseThrow();
            LocalDate start = nextUpcomingMonday(LocalDate.now(clock).plusWeeks(5));

            LeaveApplicationDto dto = new LeaveApplicationDto();
            dto.setLeaveTypeId(casualType.getId());
            dto.setStartDate(start);
            dto.setEndDate(start);
            dto.setReason("Transaction rollback notification test");

            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

            // 1. Rolled-back transaction -> must NOT send email
            txTemplate.executeWithoutResult(status -> {
                leaveService.applyForLeave(tmp.user().getId(), dto);
                status.setRollbackOnly();
            });
            verify(notificationService, after(300).never()).sendEmail(eq(tmp.user().getEmail()), anyString(), anyString());

            // 2. Committed transaction -> sends email once after commit
            LeaveRequest committed = txTemplate.execute(status -> leaveService.applyForLeave(tmp.user().getId(), dto));
            assertNotNull(committed);
            verify(notificationService, timeout(2000).times(1))
                    .sendEmail(eq(tmp.user().getEmail()), contains("Leave Request Submitted"), anyString());
        } finally {
            cleanupTempEmployee(tmp);
        }
    }

    @Test
    @DisplayName("BUG-06: Audited leave lifecycle writes structured JSONB snapshots instead of toString()")
    void auditedLeaveLifecycleWritesStructuredSnapshots() {
        User empUser = userRepository.findByEmail("emp1@company.com").orElseThrow();
        User hrUser = userRepository.findByEmail("hr1@company.com").orElseThrow();
        Employee emp = employeeRepository.findByUserId(empUser.getId()).orElseThrow();
        LeaveType casualType = leaveTypeRepository.findByCode("CASUAL").orElseThrow();

        LocalDate start = nextUpcomingMonday(LocalDate.now(clock).plusWeeks(8));
        LeaveApplicationDto dto = new LeaveApplicationDto();
        dto.setLeaveTypeId(casualType.getId());
        dto.setStartDate(start);
        dto.setEndDate(start);
        dto.setReason("Audit snapshot verification");

        LeaveRequest req = null;
        try {
            authenticateAs("emp1@company.com");
            req = leaveService.applyForLeave(empUser.getId(), dto);
            SecurityContextHolder.clearContext();

            final UUID reqId = req.getId();
            AuditLog applyAudit = findLatestAudit(reqId, "APPLY_LEAVE");
            assertNull(applyAudit.getPreviousData());
            assertNotNull(applyAudit.getNewData());
            assertNull(applyAudit.getNewData().get("resultSummary"),
                    "Audit snapshot must use structured domain fields, not fallback resultSummary");
            assertEquals("CASUAL", applyAudit.getNewData().get("leaveTypeCode"));
            assertEquals("PENDING", applyAudit.getNewData().get("status"));
            assertNotNull(applyAudit.getNewData().get("daysByYear"));

            authenticateAs("hr1@company.com");
            leaveService.approveLeave(reqId, hrUser.getId());
            SecurityContextHolder.clearContext();

            AuditLog approveAudit = findLatestAudit(reqId, "APPROVE_LEAVE");
            assertNotNull(approveAudit.getPreviousData());
            assertEquals("PENDING", approveAudit.getPreviousData().get("status"));
            assertNotNull(approveAudit.getNewData());
            assertEquals("APPROVED", approveAudit.getNewData().get("status"));
            assertEquals("hr1@company.com", approveAudit.getNewData().get("actionedBy"));
        } finally {
            SecurityContextHolder.clearContext();
            if (req != null) {
                attendanceRecordRepository.findByEmployeeIdAndWorkDate(emp.getId(), start)
                        .ifPresent(attendanceRecordRepository::delete);
                leaveRequestRepository.deleteById(req.getId());
                leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(
                        emp.getId(), casualType.getId(), start.getYear())
                        .ifPresent(bal -> {
                            bal.setUsedDays(bal.getUsedDays().subtract(BigDecimal.ONE).max(BigDecimal.ZERO));
                            bal.setRemainingDays(bal.getAllocatedDays().subtract(bal.getUsedDays()).subtract(bal.getPendingDays()));
                            leaveBalanceRepository.save(bal);
                        });
            }
        }
    }

    private AuditLog findLatestAudit(UUID targetId, String action) {
        return auditLogRepository.findAllByOrderByOccurredAtDesc(PageRequest.of(0, 50)).getContent().stream()
                .filter(a -> targetId.equals(a.getTargetId()) && action.equals(a.getAction()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing audit log for " + action + " target " + targetId));
    }

    private void assertInvariantHolds(LeaveBalance balance) {
        BigDecimal sum = balance.getRemainingDays().add(balance.getUsedDays()).add(balance.getPendingDays());
        assertEquals(0, balance.getAllocatedDays().compareTo(sum),
                "Balance invariant Allocated == Remaining + Used + Pending failed for year " + balance.getYear());
    }

    private LocalDate nextUpcomingMonday(LocalDate from) {
        LocalDate d = from;
        while (d.getDayOfWeek() != DayOfWeek.MONDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    private TempEmpContext createTempEmployee(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setEmail(prefix + "." + suffix + "@company.com");
        user.setPasswordHash(passwordEncoder.encode("TempPass#1234"));
        user.setRole(Role.EMPLOYEE);
        user.setActive(true);
        user = userRepository.save(user);

        Department dept = departmentRepository.findAll().get(0);
        JobRole jobRole = jobRoleRepository.findByDepartmentId(dept.getId()).get(0);

        Employee emp = new Employee();
        emp.setEmployeeCode("EMP-T-" + suffix.substring(0, 5).toUpperCase());
        emp.setFullName("Temp Test " + suffix);
        emp.setDepartment(dept);
        emp.setJobRole(jobRole);
        emp.setUser(user);
        emp.setDateOfJoining(LocalDate.of(2026, 1, 1));
        emp.setStatus(EmployeeStatus.ACTIVE);
        emp = employeeRepository.save(emp);

        int currYear = LocalDate.now(clock).getYear();
        leaveBalanceService.initializeBalancesForEmployee(emp, currYear);
        leaveBalanceService.initializeBalancesForEmployee(emp, currYear + 1);
        return new TempEmpContext(emp, user);
    }

    private void cleanupTempEmployee(TempEmpContext tmp) {
        attendanceRecordRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateDesc(
                tmp.employee().getId(), LocalDate.of(2020, 1, 1), LocalDate.of(2035, 12, 31))
                .forEach(attendanceRecordRepository::delete);
        leaveRequestRepository.findByEmployeeIdOrderByCreatedAtDesc(tmp.employee().getId())
                .forEach(leaveRequestRepository::delete);
        leaveBalanceRepository.findAll().stream()
                .filter(b -> b.getEmployee().getId().equals(tmp.employee().getId()))
                .forEach(leaveBalanceRepository::delete);
        employeeRepository.findById(tmp.employee().getId()).ifPresent(employeeRepository::delete);
        userRepository.findById(tmp.user().getId()).ifPresent(userRepository::delete);
    }

    private void authenticateAs(String email) {
        CustomUserDetails details = (CustomUserDetails) customUserDetailsService.loadUserByUsername(email);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private record TempEmpContext(Employee employee, User user) {
    }
}
