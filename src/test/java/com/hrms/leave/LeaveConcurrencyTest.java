package com.hrms.leave;

import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.exception.BusinessException;
import com.hrms.leave.dto.LeaveApplicationDto;
import com.hrms.leave.entity.LeaveBalance;
import com.hrms.leave.entity.LeaveRequest;
import com.hrms.leave.entity.LeaveType;
import com.hrms.leave.repository.LeaveBalanceRepository;
import com.hrms.leave.repository.LeaveRequestRepository;
import com.hrms.leave.repository.LeaveTypeRepository;
import com.hrms.leave.service.LeaveBalanceService;
import com.hrms.leave.service.LeaveService;
import com.hrms.security.entity.User;
import com.hrms.security.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 3 & Milestone 5 Gate Verification:
 * Validates concurrency safety on leave requests and verifies that:
 * 1. Concurrent requests with overlapping dates are strictly blocked (exactly 1 success).
 * 2. Concurrent requests cannot overdraw the employee's balance quota under race conditions (true multithreaded quota exhaustion).
 * 3. Strict test isolation ensures zero cross-test state leakage.
 */
@SpringBootTest
class LeaveConcurrencyTest {

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
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;
    private Employee testEmployee;
    private LeaveType annualType;
    private LeaveType casualType;
    private int currentYear;

    @BeforeEach
    void setUp() {
        testUser = userRepository.findByEmail("emp1@company.com")
                .orElseThrow(() -> new IllegalStateException("Seed user emp1@company.com not found"));
        testEmployee = employeeRepository.findByUserId(testUser.getId())
                .orElseThrow(() -> new IllegalStateException("Seed employee profile not found"));

        annualType = leaveTypeRepository.findByCode("ANNUAL")
                .orElseThrow(() -> new IllegalStateException("ANNUAL leave type not found"));
        casualType = leaveTypeRepository.findByCode("CASUAL")
                .orElseThrow(() -> new IllegalStateException("CASUAL leave type not found"));

        currentYear = LocalDate.now().getYear();
        leaveBalanceService.initializeBalancesForEmployee(testEmployee, currentYear);
        cleanupTestArtifacts();
    }

    @AfterEach
    void tearDown() {
        cleanupTestArtifacts();
    }

    private void cleanupTestArtifacts() {
        if (testEmployee == null) return;

        // Delete test leave requests
        List<LeaveRequest> testRequests = leaveRequestRepository.findAll().stream()
                .filter(r -> r.getEmployee().getId().equals(testEmployee.getId())
                        && r.getReason() != null
                        && r.getReason().startsWith("Concurrent test"))
                .toList();
        if (!testRequests.isEmpty()) {
            leaveRequestRepository.deleteAll(testRequests);
        }

        // Reset balances to safe state
        leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(testEmployee.getId(), annualType.getId(), currentYear)
                .ifPresent(b -> {
                    b.setRemainingDays(b.getAllocatedDays());
                    b.setPendingDays(BigDecimal.ZERO);
                    b.setUsedDays(BigDecimal.ZERO);
                    leaveBalanceRepository.saveAndFlush(b);
                });

        leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(testEmployee.getId(), casualType.getId(), currentYear)
                .ifPresent(b -> {
                    b.setRemainingDays(b.getAllocatedDays());
                    b.setPendingDays(BigDecimal.ZERO);
                    b.setUsedDays(BigDecimal.ZERO);
                    leaveBalanceRepository.saveAndFlush(b);
                });
    }

    @Test
    @DisplayName("Milestone 3 Gate: Concurrent overlapping leave requests result in exactly 1 success")
    void testConcurrentOverlappingLeaveRequests() throws Exception {
        // Pick next Monday and Tuesday to guarantee working days
        LocalDate nextMonday = LocalDate.now().plusDays(7);
        while (nextMonday.getDayOfWeek() != DayOfWeek.MONDAY) {
            nextMonday = nextMonday.plusDays(1);
        }
        LocalDate nextTuesday = nextMonday.plusDays(1);

        final LocalDate start = nextMonday;
        final LocalDate end = nextTuesday;

        // Clean any pre-existing request overlapping target dates
        List<LeaveRequest> existing = leaveRequestRepository.findAll().stream()
                .filter(r -> r.getEmployee().getId().equals(testEmployee.getId())
                        && r.getStartDate().equals(start) && r.getEndDate().equals(end))
                .toList();
        leaveRequestRepository.deleteAll(existing);

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    LeaveApplicationDto dto = new LeaveApplicationDto();
                    dto.setLeaveTypeId(annualType.getId());
                    dto.setStartDate(start);
                    dto.setEndDate(end);
                    dto.setReason("Concurrent test overlapping leave");

                    leaveService.applyForLeave(testUser.getId(), dto);
                    successCount.incrementAndGet();
                } catch (BusinessException ex) {
                    failureCount.incrementAndGet();
                } catch (Exception ex) {
                    failureCount.incrementAndGet();
                } finally {
                    endGate.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startGate.countDown();
        boolean completed = endGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All threads should complete execution within timeout");
        assertEquals(1, successCount.get(), "Exactly one concurrent request for overlapping dates must succeed");
        assertEquals(threads - 1, failureCount.get(), "All other concurrent requests must be rejected");
    }

    @Test
    @DisplayName("Milestone 5 Gate: True multithreaded quota exhaustion blocks overdraw under concurrency")
    void testConcurrencyBalanceHoldPreventsOverdraw() throws Exception {
        // Prepare balance: allocate 10 days, mark 8 days used, leaving exactly 2 days remaining
        LeaveBalance balance = leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(
                testEmployee.getId(), casualType.getId(), currentYear)
                .orElseThrow();
        balance.setAllocatedDays(BigDecimal.valueOf(10.0));
        balance.setUsedDays(BigDecimal.valueOf(8.0));
        balance.setPendingDays(BigDecimal.ZERO);
        balance.setRemainingDays(BigDecimal.valueOf(2.0));
        leaveBalanceRepository.saveAndFlush(balance);

        // Find 5 non-overlapping distinct future working days (Monday-Friday 3 weeks out)
        LocalDate mondayWeek3 = LocalDate.now().plusDays(21);
        while (mondayWeek3.getDayOfWeek() != DayOfWeek.MONDAY) {
            mondayWeek3 = mondayWeek3.plusDays(1);
        }

        List<LocalDate> testDates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            testDates.add(mondayWeek3.plusDays(i));
        }

        // Clean any overlapping records on those dates
        for (LocalDate d : testDates) {
            List<LeaveRequest> existing = leaveRequestRepository.findAll().stream()
                    .filter(r -> r.getEmployee().getId().equals(testEmployee.getId())
                            && !r.getStartDate().isAfter(d) && !r.getEndDate().isBefore(d))
                    .toList();
            leaveRequestRepository.deleteAll(existing);
        }

        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final LocalDate singleDate = testDates.get(i);
            executor.submit(() -> {
                try {
                    startGate.await();
                    LeaveApplicationDto dto = new LeaveApplicationDto();
                    dto.setLeaveTypeId(casualType.getId());
                    dto.setStartDate(singleDate);
                    dto.setEndDate(singleDate);
                    dto.setReason("Concurrent test quota exhaustion");

                    leaveService.applyForLeave(testUser.getId(), dto);
                    successCount.incrementAndGet();
                } catch (BusinessException ex) {
                    failureCount.incrementAndGet();
                } catch (Exception ex) {
                    failureCount.incrementAndGet();
                } finally {
                    endGate.countDown();
                }
            });
        }

        // Release all 5 threads simultaneously to compete for the 2 remaining days
        startGate.countDown();
        boolean completed = endGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All threads should complete execution within timeout");
        assertEquals(2, successCount.get(), "Exactly 2 concurrent requests must succeed when remaining quota is 2 days");
        assertEquals(3, failureCount.get(), "The remaining 3 concurrent requests must fail with insufficient quota");

        // Verify balance state and invariant: Allocated (10) == Remaining (0) + Used (8) + Pending (2)
        LeaveBalance finalBalance = leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(
                testEmployee.getId(), casualType.getId(), currentYear)
                .orElseThrow();

        assertEquals(0, BigDecimal.ZERO.compareTo(finalBalance.getRemainingDays()),
                "Remaining days must be 0 after quota exhaustion");
        assertEquals(0, BigDecimal.valueOf(2.0).compareTo(finalBalance.getPendingDays()),
                "Pending days must be 2.0 holding the 2 successful reservations");
        assertEquals(0, finalBalance.getAllocatedDays().compareTo(
                finalBalance.getRemainingDays().add(finalBalance.getUsedDays()).add(finalBalance.getPendingDays())),
                "Balance integrity invariant must hold: Allocated == Remaining + Used + Pending");
    }
}
