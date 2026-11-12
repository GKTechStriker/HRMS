package com.hrms.attendance;

import com.hrms.attendance.dto.AttendanceCorrectionDto;
import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.attendance.entity.WorkMode;
import com.hrms.attendance.repository.AttendanceRecordRepository;
import com.hrms.attendance.repository.HolidayRepository;
import com.hrms.attendance.service.AttendanceFinalizerJob;
import com.hrms.attendance.service.AttendanceService;
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
import com.hrms.leave.repository.LeaveRequestRepository;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration & regression tests for:
 * - BUG-03: Null durationMinutes handling and invalid/inverted manual correction times
 * - BUG-04: Concurrency race condition on simultaneous clockIn calls
 * - BUG-06: Structured @Audited before/after JSONB snapshots on attendance operations
 * - BUG-07 & BUG-11: AttendanceFinalizerJob recurring holiday & configured business Clock
 */
@SpringBootTest
class Part1AttendanceRegressionTest {

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private AttendanceRecordRepository attendanceRecordRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

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
    private Clock businessClock;

    @Test
    @DisplayName("BUG-11: Configured business Clock resolves Asia/Kolkata timezone")
    void businessClockConfiguredToAsiaKolkata() {
        assertEquals(ZoneId.of("Asia/Kolkata"), businessClock.getZone());
    }

    @Test
    @DisplayName("BUG-03: correctAttendance rejects inverted or malformed times and nulls duration when punch is partial")
    void manualCorrectionValidatesTimesAndNullsPartialDuration() {
        User hrUser = userRepository.findByEmail("hr1@company.com").orElseThrow();
        User empUser = userRepository.findByEmail("emp1@company.com").orElseThrow();
        Employee emp = employeeRepository.findByUserId(empUser.getId()).orElseThrow();
        LocalDate testDate = LocalDate.now(businessClock).minusDays(7);

        try {
            // 1. Inverted clockOut < clockIn
            AttendanceCorrectionDto inverted = new AttendanceCorrectionDto();
            inverted.setEmployeeId(emp.getId());
            inverted.setWorkDate(testDate);
            inverted.setStatus(AttendanceStatus.PRESENT);
            inverted.setWorkMode(WorkMode.ON_SITE);
            inverted.setClockInTime("18:00");
            inverted.setClockOutTime("09:00");
            inverted.setReason("Inverted shift test");

            BusinessException invertedEx = assertThrows(BusinessException.class,
                    () -> attendanceService.correctAttendance(inverted, hrUser.getId()));
            assertTrue(invertedEx.getMessage().contains("cannot be earlier"));

            // 2. Malformed time format
            AttendanceCorrectionDto malformed = new AttendanceCorrectionDto();
            malformed.setEmployeeId(emp.getId());
            malformed.setWorkDate(testDate);
            malformed.setStatus(AttendanceStatus.PRESENT);
            malformed.setClockInTime("invalid-hhmm");
            malformed.setReason("Malformed time check");
            assertThrows(BusinessException.class,
                    () -> attendanceService.correctAttendance(malformed, hrUser.getId()));

            // 3. Partial punch (clockOut only, no clockIn) -> durationMinutes must remain null cleanly
            AttendanceCorrectionDto partial = new AttendanceCorrectionDto();
            partial.setEmployeeId(emp.getId());
            partial.setWorkDate(testDate);
            partial.setStatus(AttendanceStatus.HALF_DAY);
            partial.setWorkMode(WorkMode.REMOTE);
            partial.setClockOutTime("18:00");
            partial.setReason("Only clock-out captured");

            authenticateAs("hr1@company.com");
            AttendanceRecord saved = attendanceService.correctAttendance(partial, hrUser.getId());
            SecurityContextHolder.clearContext();

            assertNull(saved.getClockIn());
            assertNotNull(saved.getClockOut());
            assertNull(saved.getDurationMinutes());
            assertEquals("Asia/Kolkata", saved.getClockOut().getOffset().getId().equals("+05:30") ? "Asia/Kolkata" : "");

            // BUG-06: Verify structured audit entry was written for CORRECT_ATTENDANCE
            AuditLog audit = auditLogRepository.findAllByOrderByOccurredAtDesc(PageRequest.of(0, 30)).getContent().stream()
                    .filter(a -> saved.getId().equals(a.getTargetId()) && "CORRECT_ATTENDANCE".equals(a.getAction()))
                    .findFirst()
                    .orElseThrow();
            assertNotNull(audit.getNewData());
            assertNull(audit.getNewData().get("resultSummary"));
            assertEquals("HALF_DAY", audit.getNewData().get("status"));
            assertEquals("Only clock-out captured", audit.getNewData().get("correctionReason"));
        } finally {
            SecurityContextHolder.clearContext();
            attendanceRecordRepository.findByEmployeeIdAndWorkDate(emp.getId(), testDate)
                    .ifPresent(attendanceRecordRepository::delete);
        }
    }

    @Test
    @DisplayName("BUG-04 & BUG-06: Concurrent clock-in allows exactly 1 success under pessimistic lock")
    void concurrentClockInAllowsOnlyOnePunch() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User tempUser = new User();
        tempUser.setEmail("att.conc." + suffix + "@company.com");
        tempUser.setPasswordHash(passwordEncoder.encode("TempPass#1234"));
        tempUser.setRole(Role.EMPLOYEE);
        tempUser.setActive(true);
        tempUser = userRepository.save(tempUser);

        Department dept = departmentRepository.findAll().get(0);
        JobRole jobRole = jobRoleRepository.findByDepartmentId(dept.getId()).get(0);

        Employee tempEmp = new Employee();
        tempEmp.setEmployeeCode("EMP-C-" + suffix.substring(0, 5).toUpperCase());
        tempEmp.setFullName("Concurrent Clock Employee");
        tempEmp.setDepartment(dept);
        tempEmp.setJobRole(jobRole);
        tempEmp.setUser(tempUser);
        tempEmp.setDateOfJoining(LocalDate.of(2026, 1, 1));
        tempEmp.setStatus(EmployeeStatus.ACTIVE);
        tempEmp = employeeRepository.save(tempEmp);

        final UUID tempUserId = tempUser.getId();
        final UUID tempEmpId = tempEmp.getId();

        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger businessFailures = new AtomicInteger();
        AtomicInteger unexpectedFailures = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        ready.await();
                        attendanceService.clockIn(tempUserId, WorkMode.ON_SITE, "127.0.0.1", "Race test");
                        successes.incrementAndGet();
                    } catch (BusinessException ex) {
                        businessFailures.incrementAndGet();
                    } catch (Exception ex) {
                        unexpectedFailures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.countDown();
            assertTrue(done.await(15, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(1, successes.get(), "Exactly one thread may succeed clocking in");
            assertEquals(threads - 1, businessFailures.get(), "All competing threads must fail cleanly with BusinessException");
            assertEquals(0, unexpectedFailures.get(), "Zero constraint violations or unexpected errors allowed");

            LocalDate today = LocalDate.now(businessClock);
            assertTrue(attendanceRecordRepository.findByEmployeeIdAndWorkDate(tempEmpId, today).isPresent());

            // Also verify clockIn & clockOut write structured audit entries when authenticated as emp1
            User emp1User = userRepository.findByEmail("emp1@company.com").orElseThrow();
            Employee emp1 = employeeRepository.findByUserId(emp1User.getId()).orElseThrow();
            attendanceRecordRepository.findByEmployeeIdAndWorkDate(emp1.getId(), today)
                    .ifPresent(attendanceRecordRepository::delete);

            authenticateAs("emp1@company.com");
            AttendanceRecord inRec = attendanceService.clockIn(emp1User.getId(), WorkMode.ON_SITE, "127.0.0.1", "Audit punch in");
            AttendanceRecord outRec = attendanceService.clockOut(emp1User.getId(), "127.0.0.1", "Audit punch out");
            SecurityContextHolder.clearContext();

            AuditLog inAudit = auditLogRepository.findAllByOrderByOccurredAtDesc(PageRequest.of(0, 30)).getContent().stream()
                    .filter(a -> inRec.getId().equals(a.getTargetId()) && "ATTENDANCE_CLOCK_IN".equals(a.getAction()))
                    .findFirst().orElseThrow();
            assertNotNull(inAudit.getNewData());
            assertNotNull(inAudit.getNewData().get("clockIn"));
            assertNull(inAudit.getNewData().get("resultSummary"));

            AuditLog outAudit = auditLogRepository.findAllByOrderByOccurredAtDesc(PageRequest.of(0, 30)).getContent().stream()
                    .filter(a -> outRec.getId().equals(a.getTargetId()) && "ATTENDANCE_CLOCK_OUT".equals(a.getAction()))
                    .findFirst().orElseThrow();
            assertNotNull(outAudit.getPreviousData());
            assertNull(outAudit.getPreviousData().get("clockOut"));
            assertNotNull(outAudit.getNewData().get("clockOut"));

            attendanceRecordRepository.findByEmployeeIdAndWorkDate(emp1.getId(), today)
                    .ifPresent(attendanceRecordRepository::delete);
        } finally {
            SecurityContextHolder.clearContext();
            attendanceRecordRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateDesc(
                    tempEmpId, LocalDate.of(2020, 1, 1), LocalDate.of(2035, 12, 31))
                    .forEach(attendanceRecordRepository::delete);
            employeeRepository.findById(tempEmpId).ifPresent(employeeRepository::delete);
            userRepository.findById(tempUserId).ifPresent(userRepository::delete);
        }
    }

    @Test
    @DisplayName("BUG-07 & BUG-11: AttendanceFinalizerJob uses business timezone Clock and skips recurring holidays")
    void finalizerJobUsesBusinessTimezoneAndSkipsRecurringHolidays() {
        // 2026-12-31T19:00:00Z is 2027-01-01 00:30 IST (New Year's Day, recurring Friday)
        // Under UTC it would still be Dec 31 (a regular working Thursday)!
        Clock istNewYearClock = Clock.fixed(Instant.parse("2026-12-31T19:00:00Z"), ZoneId.of("Asia/Kolkata"));
        AttendanceFinalizerJob job = new AttendanceFinalizerJob(
                attendanceRecordRepository, holidayRepository, leaveRequestRepository, istNewYearClock);

        LocalDate istDate = LocalDate.now(istNewYearClock);
        assertEquals(LocalDate.of(2027, 1, 1), istDate);
        long beforeCount = attendanceRecordRepository.countByWorkDate(istDate);

        job.finalizeDailyAttendance();

        long afterCount = attendanceRecordRepository.countByWorkDate(istDate);
        assertEquals(beforeCount, afterCount,
                "Finalizer job must resolve 2027-01-01 in Asia/Kolkata and skip creating ABSENT records on recurring holiday");
    }

    private void authenticateAs(String email) {
        CustomUserDetails details = (CustomUserDetails) customUserDetailsService.loadUserByUsername(email);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }
}
