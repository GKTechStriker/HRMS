package com.hrms.leave.service;

import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.attendance.entity.Holiday;
import com.hrms.attendance.entity.WorkMode;
import com.hrms.attendance.repository.AttendanceRecordRepository;
import com.hrms.attendance.repository.HolidayRepository;
import com.hrms.audit.AuditContext;
import com.hrms.audit.Audited;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.exception.BusinessException;
import com.hrms.exception.ResourceNotFoundException;
import com.hrms.leave.dto.LeaveApplicationDto;
import com.hrms.leave.entity.LeaveBalance;
import com.hrms.leave.entity.LeaveRequest;
import com.hrms.leave.entity.LeaveStatus;
import com.hrms.leave.entity.LeaveType;
import com.hrms.leave.event.LeaveNotificationEvent;
import com.hrms.leave.repository.LeaveBalanceRepository;
import com.hrms.leave.repository.LeaveRequestRepository;
import com.hrms.leave.repository.LeaveTypeRepository;
import com.hrms.security.entity.User;
import com.hrms.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Core business service managing leave requests, approvals,
 * and the concurrency-safe provisional hold-then-convert mechanic.
 */
@Service
public class LeaveService {

    private static final Logger log = LoggerFactory.getLogger(LeaveService.class);

    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceService leaveBalanceService;
    private final EmployeeRepository employeeRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final HolidayRepository holidayRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public LeaveService(LeaveRequestRepository leaveRequestRepository,
                        LeaveBalanceRepository leaveBalanceRepository,
                        LeaveTypeRepository leaveTypeRepository,
                        LeaveBalanceService leaveBalanceService,
                        EmployeeRepository employeeRepository,
                        AttendanceRecordRepository attendanceRecordRepository,
                        HolidayRepository holidayRepository,
                        UserRepository userRepository,
                        ApplicationEventPublisher eventPublisher,
                        Clock clock) {
        this.leaveRequestRepository = leaveRequestRepository;
        this.leaveBalanceRepository = leaveBalanceRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.leaveBalanceService = leaveBalanceService;
        this.employeeRepository = employeeRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.holidayRepository = holidayRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Submits a leave application with strict concurrency protection.
     * Places provisional holds per calendar year to support cross-year requests safely.
     */
    @Audited(action = "APPLY_LEAVE", targetType = "LEAVE_REQUEST")
    @Transactional
    public LeaveRequest applyForLeave(UUID userId, LeaveApplicationDto dto) {
        final Employee employeeRef = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("No employee profile found for current user account."));

        if (dto.getStartDate().isAfter(dto.getEndDate())) {
            throw new BusinessException("Start date cannot be after end date.");
        }

        if (dto.getStartDate().isBefore(LocalDate.now(clock))) {
            throw new BusinessException("Cannot submit leave requests for past dates.");
        }

        // Calculate working days split by calendar year (skip weekends & public holidays)
        NavigableMap<Integer, BigDecimal> daysByYear = calculateWorkingDaysByYear(dto.getStartDate(), dto.getEndDate());
        BigDecimal totalDays = daysByYear.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDays.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Selected date range contains zero working days (weekends and public holidays only).");
        }

        // 1. Acquire pessimistic write lock on the employee row to serialize concurrent leave operations
        Employee employee = employeeRepository.findByIdForUpdate(employeeRef.getId())
                .orElseThrow(() -> new BusinessException("No employee profile found for current user account."));

        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new BusinessException("Only active employees can submit leave requests.");
        }

        LeaveType leaveType = leaveTypeRepository.findById(dto.getLeaveTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Leave type not found with ID: " + dto.getLeaveTypeId()));

        // 2. Overlap Check under write lock
        boolean hasOverlap = leaveRequestRepository.existsOverlappingLeave(
                employee.getId(),
                List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED),
                dto.getStartDate(),
                dto.getEndDate()
        );
        if (hasOverlap) {
            throw new BusinessException("You already have an active or pending leave request overlapping these dates.");
        }

        // 3. Concurrency Hold Mechanic: Lock balances in ascending year order & validate quotas
        Map<Integer, LeaveBalance> lockedBalances = new LinkedHashMap<>();
        for (Map.Entry<Integer, BigDecimal> entry : daysByYear.entrySet()) {
            int year = entry.getKey();
            BigDecimal requiredForYear = entry.getValue();

            LeaveBalance balance = leaveBalanceRepository.findForUpdate(employee.getId(), leaveType.getId(), year)
                    .orElseGet(() -> {
                        leaveBalanceService.initializeBalancesForEmployee(employee, year);
                        return leaveBalanceRepository.findForUpdate(employee.getId(), leaveType.getId(), year)
                                .orElseThrow(() -> new IllegalStateException("Failed to initialize and lock leave balance for year " + year));
                    });

            if (balance.getRemainingDays().compareTo(requiredForYear) < 0) {
                throw new BusinessException(String.format(
                        "Insufficient %s balance for %d. Available: %.1f days, Requested: %.1f days.",
                        leaveType.getName(), year, balance.getRemainingDays(), requiredForYear));
            }
            lockedBalances.put(year, balance);
        }

        // Apply provisional holds across all affected years
        for (Map.Entry<Integer, BigDecimal> entry : daysByYear.entrySet()) {
            LeaveBalance balance = lockedBalances.get(entry.getKey());
            BigDecimal requiredForYear = entry.getValue();
            balance.setPendingDays(balance.getPendingDays().add(requiredForYear));
            balance.setRemainingDays(balance.getRemainingDays().subtract(requiredForYear));
            leaveBalanceRepository.save(balance);
        }

        LeaveRequest request = new LeaveRequest(
                employee,
                leaveType,
                dto.getStartDate(),
                dto.getEndDate(),
                totalDays,
                dto.getReason().trim()
        );

        LeaveRequest saved = leaveRequestRepository.saveAndFlush(request);
        AuditContext.setTarget(saved.getId(), null, toAuditSnapshot(saved, daysByYear));

        log.info("Leave request #{} submitted by {} for {} days ({}) - Provisional hold applied across years {}.",
                saved.getId(), employee.getFullName(), totalDays, leaveType.getName(), daysByYear.keySet());

        eventPublisher.publishEvent(new LeaveNotificationEvent(
                employee.getUser() != null ? employee.getUser().getEmail() : "employee@company.com",
                "Leave Request Submitted (" + leaveType.getName() + ")",
                String.format("Dear %s,\n\nYour leave request for %.1f days (%s to %s) has been submitted and is pending HR approval.\n\nReason: %s",
                        employee.getFullName(), totalDays, dto.getStartDate(), dto.getEndDate(), dto.getReason())
        ));

        return saved;
    }

    /**
     * HR Admin Approval:
     * Converts provisional hold to used days across affected years, updates request status to APPROVED,
     * and synchronizes daily attendance records to ON_LEAVE without overwriting punched records.
     */
    @Audited(action = "APPROVE_LEAVE", targetType = "LEAVE_REQUEST")
    @Transactional
    public LeaveRequest approveLeave(UUID requestId, UUID hrUserId) {
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found with ID: " + requestId));

        if (request.getStatus() != LeaveStatus.PENDING) {
            throw new BusinessException("Only PENDING requests can be approved. Current status: " + request.getStatus());
        }

        User hrUser = userRepository.findById(hrUserId)
                .orElseThrow(() -> new ResourceNotFoundException("HR User not found"));

        employeeRepository.findByIdForUpdate(request.getEmployee().getId());

        Map<String, Object> previousState = toAuditSnapshot(request, null);

        NavigableMap<Integer, BigDecimal> breakdown = resolveHeldBreakdown(request);
        for (Map.Entry<Integer, BigDecimal> entry : breakdown.entrySet()) {
            int year = entry.getKey();
            BigDecimal daysForYear = entry.getValue();
            if (daysForYear.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            LeaveBalance balance = leaveBalanceRepository.findForUpdate(
                    request.getEmployee().getId(), request.getLeaveType().getId(), year)
                    .orElseThrow(() -> new ResourceNotFoundException("Leave balance not found for locking (year " + year + ")"));

            balance.setPendingDays(balance.getPendingDays().subtract(daysForYear));
            balance.setUsedDays(balance.getUsedDays().add(daysForYear));
            leaveBalanceRepository.save(balance);
        }

        request.setStatus(LeaveStatus.APPROVED);
        request.setActionedBy(hrUser);
        request.setActionedAt(OffsetDateTime.now(clock));
        LeaveRequest approved = leaveRequestRepository.saveAndFlush(request);

        // Synchronize Daily Attendance Records to ON_LEAVE for working non-holiday days only
        syncAttendanceToOnLeave(request.getEmployee(), request.getStartDate(), request.getEndDate(), request.getLeaveType().getName());

        AuditContext.setTarget(approved.getId(), previousState, toAuditSnapshot(approved, breakdown));

        log.info("Leave request #{} for {} APPROVED by HR {}",
                approved.getId(), approved.getEmployee().getFullName(), hrUser.getEmail());

        eventPublisher.publishEvent(new LeaveNotificationEvent(
                request.getEmployee().getUser() != null ? request.getEmployee().getUser().getEmail() : "employee@company.com",
                "Leave Request APPROVED: " + request.getLeaveType().getName(),
                String.format("Dear %s,\n\nGreat news! Your leave request from %s to %s (%.1f days) has been APPROVED by HR.",
                        request.getEmployee().getFullName(), request.getStartDate(), request.getEndDate(), request.getTotalDays())
        ));

        return approved;
    }

    /**
     * HR Admin Rejection:
     * Releases provisional hold back to available balance across affected years and records explanation in audit log.
     */
    @Audited(action = "REJECT_LEAVE", targetType = "LEAVE_REQUEST")
    @Transactional
    public LeaveRequest rejectLeave(UUID requestId, String rejectionReason, UUID hrUserId) {
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found with ID: " + requestId));

        if (request.getStatus() != LeaveStatus.PENDING) {
            throw new BusinessException("Only PENDING requests can be rejected. Current status: " + request.getStatus());
        }

        User hrUser = userRepository.findById(hrUserId)
                .orElseThrow(() -> new ResourceNotFoundException("HR User not found"));

        employeeRepository.findByIdForUpdate(request.getEmployee().getId());

        Map<String, Object> previousState = toAuditSnapshot(request, null);

        NavigableMap<Integer, BigDecimal> breakdown = releasePendingHold(request);

        request.setStatus(LeaveStatus.REJECTED);
        request.setActionedBy(hrUser);
        request.setActionedAt(OffsetDateTime.now(clock));
        request.setRejectionReason(rejectionReason != null ? rejectionReason.trim() : "Rejected by HR");
        LeaveRequest rejected = leaveRequestRepository.saveAndFlush(request);

        AuditContext.setTarget(rejected.getId(), previousState, toAuditSnapshot(rejected, breakdown));

        log.info("Leave request #{} for {} REJECTED by HR {}. Reason: {}",
                rejected.getId(), rejected.getEmployee().getFullName(), hrUser.getEmail(), rejectionReason);

        eventPublisher.publishEvent(new LeaveNotificationEvent(
                request.getEmployee().getUser() != null ? request.getEmployee().getUser().getEmail() : "employee@company.com",
                "Leave Request Declined",
                String.format("Dear %s,\n\nYour leave request for %.1f days (%s to %s) was not approved.\n\nReason from HR: %s",
                        request.getEmployee().getFullName(), request.getTotalDays(), request.getStartDate(), request.getEndDate(), rejected.getRejectionReason())
        ));

        return rejected;
    }

    /**
     * Allows an employee to cancel their own pending leave request and releases provisional holds.
     */
    @Audited(action = "CANCEL_LEAVE", targetType = "LEAVE_REQUEST")
    @Transactional
    public LeaveRequest cancelLeave(UUID requestId, UUID userId) {
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found with ID: " + requestId));

        if (request.getEmployee().getUser() == null || !request.getEmployee().getUser().getId().equals(userId)) {
            throw new BusinessException("You are only permitted to cancel your own leave requests.");
        }

        if (request.getStatus() != LeaveStatus.PENDING) {
            throw new BusinessException("Only PENDING leave requests can be cancelled.");
        }

        employeeRepository.findByIdForUpdate(request.getEmployee().getId());

        Map<String, Object> previousState = toAuditSnapshot(request, null);
        NavigableMap<Integer, BigDecimal> breakdown = releasePendingHold(request);

        request.setStatus(LeaveStatus.CANCELLED);
        LeaveRequest cancelled = leaveRequestRepository.saveAndFlush(request);

        AuditContext.setTarget(cancelled.getId(), previousState, toAuditSnapshot(cancelled, breakdown));
        return cancelled;
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> getPendingRequests() {
        return leaveRequestRepository.findByStatusWithDetails(LeaveStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> getEmployeeRequests(UUID employeeId) {
        return leaveRequestRepository.findByEmployeeIdWithLeaveTypeOrderByCreatedAtDesc(employeeId);
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> getAllRequests() {
        return leaveRequestRepository.findAllWithDetails();
    }

    @Transactional(readOnly = true)
    public long getPendingCount() {
        return leaveRequestRepository.countByStatus(LeaveStatus.PENDING);
    }

    /**
     * Calculates working days between start and end (inclusive), excluding weekends and
     * registered public holidays (both fixed dates and recurring MonthDay entries),
     * partitioned cleanly per calendar year in ascending year order.
     */
    private NavigableMap<Integer, BigDecimal> calculateWorkingDaysByYear(LocalDate start, LocalDate end) {
        HolidayLookup holidays = loadHolidayLookup(start, end);
        NavigableMap<Integer, BigDecimal> byYear = new TreeMap<>();
        LocalDate curr = start;
        while (!curr.isAfter(end)) {
            if (isWorkingDay(curr, holidays)) {
                byYear.merge(curr.getYear(), BigDecimal.ONE, BigDecimal::add);
            }
            curr = curr.plusDays(1);
        }
        return byYear;
    }

    private NavigableMap<Integer, BigDecimal> releasePendingHold(LeaveRequest request) {
        NavigableMap<Integer, BigDecimal> breakdown = resolveHeldBreakdown(request);
        for (Map.Entry<Integer, BigDecimal> entry : breakdown.entrySet()) {
            int year = entry.getKey();
            BigDecimal daysForYear = entry.getValue();
            if (daysForYear.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            LeaveBalance balance = leaveBalanceRepository.findForUpdate(
                    request.getEmployee().getId(), request.getLeaveType().getId(), year)
                    .orElseThrow(() -> new ResourceNotFoundException("Leave balance not found for year " + year));

            balance.setPendingDays(balance.getPendingDays().subtract(daysForYear));
            balance.setRemainingDays(balance.getRemainingDays().add(daysForYear));
            leaveBalanceRepository.save(balance);
        }
        return breakdown;
    }

    /**
     * Resolves the exact per-year breakdown matching request.getTotalDays().
     * In the standard path, recomputed working days match request.getTotalDays();
     * if holidays were added/edited after submission, reconciles cleanly against existing pending holds.
     */
    private NavigableMap<Integer, BigDecimal> resolveHeldBreakdown(LeaveRequest request) {
        NavigableMap<Integer, BigDecimal> computed = calculateWorkingDaysByYear(request.getStartDate(), request.getEndDate());
        BigDecimal computedTotal = computed.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (computedTotal.compareTo(request.getTotalDays()) == 0) {
            return computed;
        }

        NavigableMap<Integer, BigDecimal> reconciled = new TreeMap<>();
        BigDecimal remainingToMatch = request.getTotalDays();
        for (int year = request.getStartDate().getYear(); year <= request.getEndDate().getYear(); year++) {
            if (remainingToMatch.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            Optional<LeaveBalance> balOpt = leaveBalanceRepository.findForUpdate(
                    request.getEmployee().getId(), request.getLeaveType().getId(), year);
            if (balOpt.isPresent()) {
                BigDecimal availablePending = balOpt.get().getPendingDays();
                if (availablePending.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal allocation = availablePending.min(remainingToMatch);
                    reconciled.put(year, allocation);
                    remainingToMatch = remainingToMatch.subtract(allocation);
                }
            }
        }
        if (remainingToMatch.compareTo(BigDecimal.ZERO) > 0) {
            reconciled.merge(request.getStartDate().getYear(), remainingToMatch, BigDecimal::add);
        }
        return reconciled;
    }

    private void syncAttendanceToOnLeave(Employee employee, LocalDate start, LocalDate end, String leaveTypeName) {
        HolidayLookup holidays = loadHolidayLookup(start, end);
        LocalDate curr = start;
        while (!curr.isAfter(end)) {
            if (isWorkingDay(curr, holidays)) {
                final LocalDate day = curr;
                Optional<AttendanceRecord> existingOpt = attendanceRecordRepository
                        .findByEmployeeIdAndWorkDate(employee.getId(), day);

                if (existingOpt.isPresent()) {
                    AttendanceRecord existing = existingOpt.get();
                    if (existing.getClockIn() != null || existing.getClockOut() != null || existing.isManuallyCorrected()) {
                        log.info("Preserving existing punched/corrected attendance record on {} for employee {} (status={}).",
                                day, employee.getEmployeeCode(), existing.getStatus());
                        curr = curr.plusDays(1);
                        continue;
                    }
                    existing.setStatus(AttendanceStatus.ON_LEAVE);
                    existing.setNotes("Approved Leave: " + leaveTypeName);
                    attendanceRecordRepository.save(existing);
                } else {
                    AttendanceRecord record = new AttendanceRecord(employee, day, AttendanceStatus.ON_LEAVE, WorkMode.ON_SITE);
                    record.setNotes("Approved Leave: " + leaveTypeName);
                    attendanceRecordRepository.save(record);
                }
            }
            curr = curr.plusDays(1);
        }
    }

    private HolidayLookup loadHolidayLookup(LocalDate start, LocalDate end) {
        Set<LocalDate> exactDates = new HashSet<>();
        for (Holiday holiday : holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(start, end)) {
            exactDates.add(holiday.getHolidayDate());
        }
        Set<MonthDay> recurringMonthDays = new HashSet<>();
        for (Holiday holiday : holidayRepository.findByRecurringTrue()) {
            recurringMonthDays.add(MonthDay.from(holiday.getHolidayDate()));
        }
        return new HolidayLookup(exactDates, recurringMonthDays);
    }

    private boolean isWorkingDay(LocalDate day, HolidayLookup holidays) {
        DayOfWeek dow = day.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidays.exactDates().contains(day)
                && !holidays.recurringMonthDays().contains(MonthDay.from(day));
    }

    private Map<String, Object> toAuditSnapshot(LeaveRequest req, Map<Integer, BigDecimal> daysByYear) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("employeeCode", req.getEmployee() != null ? req.getEmployee().getEmployeeCode() : null);
        map.put("employeeName", req.getEmployee() != null ? req.getEmployee().getFullName() : null);
        map.put("leaveTypeCode", req.getLeaveType() != null ? req.getLeaveType().getCode() : null);
        map.put("startDate", req.getStartDate() != null ? req.getStartDate().toString() : null);
        map.put("endDate", req.getEndDate() != null ? req.getEndDate().toString() : null);
        map.put("totalDays", req.getTotalDays());
        map.put("status", req.getStatus() != null ? req.getStatus().name() : null);
        map.put("reason", req.getReason());
        map.put("rejectionReason", req.getRejectionReason());
        map.put("actionedBy", req.getActionedBy() != null ? req.getActionedBy().getEmail() : null);
        map.put("actionedAt", req.getActionedAt() != null ? req.getActionedAt().toString() : null);
        if (daysByYear != null && !daysByYear.isEmpty()) {
            Map<String, BigDecimal> yearMap = new LinkedHashMap<>();
            daysByYear.forEach((yr, d) -> yearMap.put(String.valueOf(yr), d));
            map.put("daysByYear", yearMap);
        }
        return map;
    }

    private record HolidayLookup(Set<LocalDate> exactDates, Set<MonthDay> recurringMonthDays) {
    }
}
