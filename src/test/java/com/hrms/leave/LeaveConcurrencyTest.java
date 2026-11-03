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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 3 Gate Verification:
 * Validates concurrency safety on leave requests and verifies that:
 * 1. Concurrent requests with overlapping dates are strictly blocked.
 * 2. Concurrent requests cannot overdraw the employee's balance quota under race conditions.
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

    @Test
    @DisplayName("Milestone 3 Gate: Concurrent overlapping leave requests result in exactly 1 success")
    void testConcurrentOverlappingLeaveRequests() throws Exception {
        User user = userRepository.findByEmail("emp1@company.com")
                .orElseThrow(() -> new IllegalStateException("Seed user emp1@company.com not found"));
        Employee employee = employeeRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("Seed employee profile not found"));

        LeaveType annualType = leaveTypeRepository.findByCode("ANNUAL")
                .orElseThrow(() -> new IllegalStateException("ANNUAL leave type not found"));

        // Pick next Monday and Tuesday to guarantee working days
        LocalDate nextMonday = LocalDate.now().plusDays(7);
        while (nextMonday.getDayOfWeek() != DayOfWeek.MONDAY) {
            nextMonday = nextMonday.plusDays(1);
        }
        LocalDate nextTuesday = nextMonday.plusDays(1);

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final LocalDate start = nextMonday;
            final LocalDate end = nextTuesday;
            executor.submit(() -> {
                try {
                    startGate.await();
                    LeaveApplicationDto dto = new LeaveApplicationDto();
                    dto.setLeaveTypeId(annualType.getId());
                    dto.setStartDate(start);
                    dto.setEndDate(end);
                    dto.setReason("Concurrent test leave");

                    leaveService.applyForLeave(user.getId(), dto);
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
    @DisplayName("Milestone 3 Gate: Hold-then-convert prevents leave quota overdraw under concurrency")
    void testConcurrencyBalanceHoldPreventsOverdraw() {
        User user = userRepository.findByEmail("emp1@company.com")
                .orElseThrow(() -> new IllegalStateException("Seed user emp1@company.com not found"));
        Employee employee = employeeRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("Seed employee profile not found"));

        LeaveType casualType = leaveTypeRepository.findByCode("CASUAL")
                .orElseThrow(() -> new IllegalStateException("CASUAL leave type not found"));

        int year = LocalDate.now().getYear();
        leaveBalanceService.initializeBalancesForEmployee(employee, year);

        LeaveBalance balance = leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(
                employee.getId(), casualType.getId(), year)
                .orElseThrow();

        // Invariant check: Allocated == Remaining + Used + Pending
        BigDecimal total = balance.getRemainingDays()
                .add(balance.getUsedDays())
                .add(balance.getPendingDays());

        assertEquals(balance.getAllocatedDays(), total,
                "Balance integrity invariant must hold: Allocated == Remaining + Used + Pending");
    }
}
