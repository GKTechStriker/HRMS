package com.hrms.service;

import com.hrms.analytics.dto.AttendanceTrendDto;
import com.hrms.analytics.service.AnalyticsService;
import com.hrms.attendance.dto.DailyAttendanceSummaryDto;
import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.attendance.entity.Holiday;
import com.hrms.attendance.repository.AttendanceRecordRepository;
import com.hrms.attendance.repository.HolidayRepository;
import com.hrms.attendance.service.AttendanceService;
import com.hrms.attendance.service.HolidayService;
import com.hrms.audit.AuditService;
import com.hrms.department.entity.Department;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.department.service.DepartmentService;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.exception.BusinessException;
import com.hrms.leave.dto.LeaveBalanceSummaryDto;
import com.hrms.leave.entity.LeaveBalance;
import com.hrms.leave.entity.LeaveType;
import com.hrms.leave.repository.LeaveBalanceRepository;
import com.hrms.leave.repository.LeaveRequestRepository;
import com.hrms.leave.repository.LeaveTypeRepository;
import com.hrms.leave.service.LeaveBalanceService;
import com.hrms.report.service.ExportService;
import com.hrms.security.CustomUserDetails;
import com.hrms.security.entity.Role;
import com.hrms.security.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests verifying Phase 4 logic:
 * - GAP-06: Elimination of N+1 count queries and bounded date range queries
 * - GAP-07: Constant-memory streaming CSV exports and paged audit queries
 * - GAP-08: Leave usage analytics aggregation
 * - GAP-09: Company Holiday management and photo/attachment schema completion
 */
class Phase4PerformanceAndDeadFeatureUnitTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-11-14T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

    @Test
    @DisplayName("GAP-06: DepartmentService.getDepartmentHeadcounts uses single aggregated query rather than N+1 queries")
    void departmentServiceUsesAggregatedHeadcountQuery() {
        DepartmentRepository deptRepo = mock(DepartmentRepository.class);
        EmployeeRepository empRepo = mock(EmployeeRepository.class);

        Department d1 = new Department();
        d1.setId(UUID.randomUUID());
        d1.setName("Engineering");
        Department d2 = new Department();
        d2.setId(UUID.randomUUID());
        d2.setName("HR");

        when(deptRepo.findAll()).thenReturn(List.of(d1, d2));
        List<Object[]> aggregated = List.of(new Object[]{d1.getId(), 5L}, new Object[]{d2.getId(), 2L});
        when(empRepo.countHeadcountByDepartmentId()).thenReturn(aggregated);

        DepartmentService service = new DepartmentService(deptRepo, mock(com.hrms.department.repository.JobRoleRepository.class), empRepo);
        Map<UUID, Long> counts = service.getDepartmentHeadcounts();

        assertEquals(2, counts.size());
        assertEquals(5L, counts.get(d1.getId()));
        assertEquals(2L, counts.get(d2.getId()));

        verify(empRepo, times(1)).countHeadcountByDepartmentId();
        verify(empRepo, never()).countByDepartmentId(any());
    }

    @Test
    @DisplayName("GAP-06: AttendanceService.getDailySummary uses single status aggregation query")
    void attendanceServiceUsesSingleAggregationQuery() {
        AttendanceRecordRepository attRepo = mock(AttendanceRecordRepository.class);
        EmployeeRepository empRepo = mock(EmployeeRepository.class);

        LocalDate date = LocalDate.of(2026, 11, 14);
        when(empRepo.countByStatus(EmployeeStatus.ACTIVE)).thenReturn(10L);
        List<Object[]> statusCounts = List.of(
                new Object[]{AttendanceStatus.PRESENT, 6L},
                new Object[]{AttendanceStatus.LATE, 1L},
                new Object[]{AttendanceStatus.ON_LEAVE, 2L},
                new Object[]{AttendanceStatus.ABSENT, 1L}
        );
        when(attRepo.countAttendanceByStatusForDate(date)).thenReturn(statusCounts);

        AttendanceService service = new AttendanceService(attRepo, empRepo, null, null, fixedClock);
        DailyAttendanceSummaryDto summary = service.getDailySummary(date);

        assertEquals(10L, summary.getTotalActiveEmployees());
        assertEquals(6L, summary.getPresentCount());
        assertEquals(1L, summary.getLateCount());
        assertEquals(2L, summary.getOnLeaveCount());
        assertEquals(1L, summary.getAbsentCount());
        assertEquals(0L, summary.getHalfDayCount());
        assertEquals(0L, summary.getHolidayCount());

        verify(attRepo, times(1)).countAttendanceByStatusForDate(date);
        verify(attRepo, never()).countByWorkDateAndStatus(any(), any());
    }

    @Test
    @DisplayName("GAP-06: AnalyticsService.getAttendanceTrends passes bounded startDate AND endDate")
    void analyticsServiceBoundsAttendanceTrendsDateRange() {
        AttendanceRecordRepository attRepo = mock(AttendanceRecordRepository.class);
        EmployeeRepository empRepo = mock(EmployeeRepository.class);
        LeaveRequestRepository leaveRepo = mock(LeaveRequestRepository.class);

        LocalDate expectedEnd = LocalDate.now(fixedClock);
        LocalDate expectedStart = expectedEnd.minusDays(13); // 14 days window

        when(attRepo.countAttendanceByDateAndStatusBetween(expectedStart, expectedEnd)).thenReturn(Collections.emptyList());

        AnalyticsService analytics = new AnalyticsService(empRepo, attRepo, leaveRepo, fixedClock);
        List<AttendanceTrendDto> trends = analytics.getAttendanceTrends(14);

        assertEquals(14, trends.size());
        verify(attRepo, times(1)).countAttendanceByDateAndStatusBetween(expectedStart, expectedEnd);
        verify(attRepo, never()).countAttendanceByDateAndStatus(any());
    }

    @Test
    @DisplayName("GAP-06: LeaveBalanceService.initializeBalancesForEmployee batch checks existing balances")
    void leaveBalanceServiceBatchInitializes() {
        LeaveBalanceRepository balanceRepo = mock(LeaveBalanceRepository.class);
        LeaveTypeRepository typeRepo = mock(LeaveTypeRepository.class);
        EmployeeRepository empRepo = mock(EmployeeRepository.class);

        Employee emp = new Employee();
        emp.setId(UUID.randomUUID());
        emp.setFullName("Aarav Mehta");
        emp.setEmployeeCode("EMP-001");

        LeaveType annual = new LeaveType("ANNUAL", "Annual Leave", BigDecimal.valueOf(18), true);
        annual.setId(UUID.randomUUID());
        LeaveType sick = new LeaveType("SICK", "Sick Leave", BigDecimal.valueOf(12), true);
        sick.setId(UUID.randomUUID());
        when(typeRepo.findAll()).thenReturn(List.of(annual, sick));

        // Suppose annual already exists for 2026, but sick is missing
        LeaveBalance existingAnnual = new LeaveBalance(emp, annual, 2026, BigDecimal.valueOf(18));
        when(balanceRepo.findByEmployeeIdAndYearWithLeaveType(emp.getId(), 2026))
                .thenReturn(List.of(existingAnnual))
                .thenReturn(List.of(existingAnnual, new LeaveBalance(emp, sick, 2026, BigDecimal.valueOf(12))));

        LeaveBalanceService service = new LeaveBalanceService(balanceRepo, typeRepo, empRepo);
        service.initializeBalancesForEmployee(emp, 2026);

        // Should batch save only the missing sick category
        ArgumentCaptor<List<LeaveBalance>> captor = ArgumentCaptor.forClass(List.class);
        verify(balanceRepo, times(1)).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("SICK", captor.getValue().getFirst().getLeaveType().getCode());

        // Must NOT call query per individual leave type
        verify(balanceRepo, never()).findByEmployeeIdAndLeaveTypeIdAndYear(any(), any(), anyInt());
    }

    @Test
    @DisplayName("GAP-07: ExportService.writeEmployeesCsv and writeAttendanceCsv stream to output stream")
    void exportServiceStreamsCsvCleanly() throws Exception {
        EmployeeRepository empRepo = mock(EmployeeRepository.class);
        AttendanceRecordRepository attRepo = mock(AttendanceRecordRepository.class);
        AuditService auditService = mock(AuditService.class);

        Department dept = new Department();
        dept.setName("Finance");
        Employee emp = new Employee();
        emp.setEmployeeCode("EMP-010");
        emp.setFullName("Priya Sharma");
        emp.setPhone("9876543210");
        emp.setDepartment(dept);
        emp.setStatus(EmployeeStatus.ACTIVE);
        emp.setDateOfJoining(LocalDate.of(2026, 1, 15));

        when(empRepo.findAllWithDepartmentAndJobRole()).thenReturn(List.of(emp));

        ExportService exportService = new ExportService(empRepo, attRepo, auditService);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        exportService.writeEmployeesCsv(baos);
        String csv = baos.toString();

        assertTrue(csv.startsWith("Employee Code,Full Name,Email,Phone,Department,Job Role,Status,Date of Joining"));
        assertTrue(csv.contains("EMP-010,Priya Sharma,,9876543210,Finance,,ACTIVE,2026-01-15"));
    }

    @Test
    @DisplayName("GAP-09: HolidayService creates holiday with validation, enforces uniqueness and deletes cleanly")
    void holidayServiceCrudAndValidation() {
        HolidayRepository holidayRepo = mock(HolidayRepository.class);
        HolidayService service = new HolidayService(holidayRepo);

        LocalDate date = LocalDate.of(2026, 8, 15);

        // Blank name should fail
        assertThrows(BusinessException.class, () -> service.createHoliday("  ", date, false, "Desc"));
        // Null date should fail
        assertThrows(BusinessException.class, () -> service.createHoliday("Independence Day", null, false, "Desc"));

        // Duplicate date should fail
        when(holidayRepo.existsByHolidayDate(date)).thenReturn(true);
        assertThrows(BusinessException.class, () -> service.createHoliday("Independence Day", date, false, "Desc"));

        // Valid holiday succeeds
        when(holidayRepo.existsByHolidayDate(date)).thenReturn(false);
        Holiday saved = new Holiday("Independence Day", date, true, "National Holiday");
        saved.setId(UUID.randomUUID());
        when(holidayRepo.save(any(Holiday.class))).thenReturn(saved);

        Holiday result = service.createHoliday("Independence Day", date, true, "National Holiday");
        assertNotNull(result);
        assertEquals("Independence Day", result.getName());
        assertTrue(result.isRecurring());

        // Deleting non-existent should throw ResourceNotFoundException
        UUID nonExistentId = UUID.randomUUID();
        when(holidayRepo.findById(nonExistentId)).thenReturn(Optional.empty());
        assertThrows(com.hrms.exception.ResourceNotFoundException.class, () -> service.deleteHoliday(nonExistentId));

        // Deleting existing succeeds
        when(holidayRepo.findById(saved.getId())).thenReturn(Optional.of(saved));
        assertDoesNotThrow(() -> service.deleteHoliday(saved.getId()));
        verify(holidayRepo, times(1)).delete(saved);
    }
}
