package com.hrms.leave.service;

import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.attendance.entity.WorkMode;
import com.hrms.attendance.repository.AttendanceRecordRepository;
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
import com.hrms.leave.repository.LeaveBalanceRepository;
import com.hrms.leave.repository.LeaveRequestRepository;
import com.hrms.leave.repository.LeaveTypeRepository;
import com.hrms.notification.service.NotificationService;
import com.hrms.security.entity.User;
import com.hrms.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public LeaveService(LeaveRequestRepository leaveRequestRepository,
                        LeaveBalanceRepository leaveBalanceRepository,
                        LeaveTypeRepository leaveTypeRepository,
                        LeaveBalanceService leaveBalanceService,
                        EmployeeRepository employeeRepository,
                        AttendanceRecordRepository attendanceRecordRepository,
                        UserRepository userRepository,
                        NotificationService notificationService) {
        this.leaveRequestRepository = leaveRequestRepository;
        this.leaveBalanceRepository = leaveBalanceRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.leaveBalanceService = leaveBalanceService;
        this.employeeRepository = employeeRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    /**
     * Submits a leave application with strict concurrency protection.
     * Places a provisional hold on the employee's balance to prevent race-condition overdrafts.
     */
    @Transactional
    public LeaveRequest applyForLeave(UUID userId, LeaveApplicationDto dto) {
        final Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("No employee profile found for current user account."));

        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new BusinessException("Only active employees can submit leave requests.");
        }

        if (dto.getStartDate().isAfter(dto.getEndDate())) {
            throw new BusinessException("Start date cannot be after end date.");
        }

        if (dto.getStartDate().isBefore(LocalDate.now())) {
            throw new BusinessException("Cannot submit leave requests for past dates.");
        }

        // Calculate working days (skip weekends)
        BigDecimal totalDays = calculateWorkingDays(dto.getStartDate(), dto.getEndDate());
        if (totalDays.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Selected date range contains zero working days (weekends only).");
        }

        // 1. Acquire pessimistic write lock on the employee row to serialize concurrent leave applications
        employeeRepository.findByIdForUpdate(employee.getId());

        LeaveType leaveType = leaveTypeRepository.findById(dto.getLeaveTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Leave type not found with ID: " + dto.getLeaveTypeId()));

        int year = dto.getStartDate().getYear();

        // 2. Concurrency Hold Mechanic: Acquire pessimistic lock on balance row
        LeaveBalance balance = leaveBalanceRepository.findForUpdate(employee.getId(), leaveType.getId(), year)
                .orElseGet(() -> {
                    leaveBalanceService.initializeBalancesForEmployee(employee, year);
                    return leaveBalanceRepository.findForUpdate(employee.getId(), leaveType.getId(), year)
                            .orElseThrow(() -> new IllegalStateException("Failed to initialize and lock leave balance"));
                });

        // 3. Overlap Check under write lock: Ensure no existing PENDING or APPROVED requests cover these dates.
        // Executing this inside the locked critical section guarantees concurrent requests see newly committed entries.
        boolean hasOverlap = leaveRequestRepository.existsOverlappingLeave(
                employee.getId(),
                List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED),
                dto.getStartDate(),
                dto.getEndDate()
        );
        if (hasOverlap) {
            throw new BusinessException("You already have an active or pending leave request overlapping these dates.");
        }

        if (balance.getRemainingDays().compareTo(totalDays) < 0) {
            throw new BusinessException(String.format(
                    "Insufficient %s balance. Available: %.1f days, Requested: %.1f days.",
                    leaveType.getName(), balance.getRemainingDays(), totalDays));
        }

        // Apply Provisional Hold: Move days from remaining to pending
        balance.setPendingDays(balance.getPendingDays().add(totalDays));
        balance.setRemainingDays(balance.getRemainingDays().subtract(totalDays));
        leaveBalanceRepository.save(balance);

        LeaveRequest request = new LeaveRequest(
                employee,
                leaveType,
                dto.getStartDate(),
                dto.getEndDate(),
                totalDays,
                dto.getReason().trim()
        );

        LeaveRequest saved = leaveRequestRepository.saveAndFlush(request);

        log.info("Leave request #{} submitted by {} for {} days ({}) - Provisional hold applied.",
                saved.getId(), employee.getFullName(), totalDays, leaveType.getName());

        notificationService.sendEmail(
                employee.getUser() != null ? employee.getUser().getEmail() : "employee@company.com",
                "Leave Request Submitted (" + leaveType.getName() + ")",
                String.format("Dear %s,\n\nYour leave request for %.1f days (%s to %s) has been submitted and is pending HR approval.\n\nReason: %s",
                        employee.getFullName(), totalDays, dto.getStartDate(), dto.getEndDate(), dto.getReason())
        );

        return saved;
    }

    /**
     * HR Admin Approval:
     * Converts provisional hold to used days, updates request status to APPROVED,
     * and automatically flags corresponding daily attendance records as ON_LEAVE.
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

        int year = request.getStartDate().getYear();
        LeaveBalance balance = leaveBalanceRepository.findForUpdate(
                request.getEmployee().getId(), request.getLeaveType().getId(), year)
                .orElseThrow(() -> new ResourceNotFoundException("Leave balance not found for locking"));

        // Convert Provisional Hold -> Used
        balance.setPendingDays(balance.getPendingDays().subtract(request.getTotalDays()));
        balance.setUsedDays(balance.getUsedDays().add(request.getTotalDays()));
        leaveBalanceRepository.save(balance);

        request.setStatus(LeaveStatus.APPROVED);
        request.setActionedBy(hrUser);
        request.setActionedAt(OffsetDateTime.now());
        LeaveRequest approved = leaveRequestRepository.save(request);

        // Synchronize Daily Attendance Records to ON_LEAVE for all working days
        syncAttendanceToOnLeave(request.getEmployee(), request.getStartDate(), request.getEndDate(), request.getLeaveType().getName());

        log.info("Leave request #{} for {} APPROVED by HR {}",
                approved.getId(), approved.getEmployee().getFullName(), hrUser.getEmail());

        notificationService.sendEmail(
                request.getEmployee().getUser() != null ? request.getEmployee().getUser().getEmail() : "employee@company.com",
                "Leave Request APPROVED: " + request.getLeaveType().getName(),
                String.format("Dear %s,\n\nGreat news! Your leave request from %s to %s (%.1f days) has been APPROVED by HR.",
                        request.getEmployee().getFullName(), request.getStartDate(), request.getEndDate(), request.getTotalDays())
        );

        return approved;
    }

    /**
     * HR Admin Rejection:
     * Releases provisional hold back to available balance and records explanation in audit log.
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

        int year = request.getStartDate().getYear();
        LeaveBalance balance = leaveBalanceRepository.findForUpdate(
                request.getEmployee().getId(), request.getLeaveType().getId(), year)
                .orElseThrow(() -> new ResourceNotFoundException("Leave balance not found for locking"));

        // Release Provisional Hold -> Back to Remaining
        balance.setPendingDays(balance.getPendingDays().subtract(request.getTotalDays()));
        balance.setRemainingDays(balance.getRemainingDays().add(request.getTotalDays()));
        leaveBalanceRepository.save(balance);

        request.setStatus(LeaveStatus.REJECTED);
        request.setActionedBy(hrUser);
        request.setActionedAt(OffsetDateTime.now());
        request.setRejectionReason(rejectionReason != null ? rejectionReason.trim() : "Rejected by HR");
        LeaveRequest rejected = leaveRequestRepository.save(request);

        log.info("Leave request #{} for {} REJECTED by HR {}. Reason: {}",
                rejected.getId(), rejected.getEmployee().getFullName(), hrUser.getEmail(), rejectionReason);

        notificationService.sendEmail(
                request.getEmployee().getUser() != null ? request.getEmployee().getUser().getEmail() : "employee@company.com",
                "Leave Request Declined",
                String.format("Dear %s,\n\nYour leave request for %.1f days (%s to %s) was not approved.\n\nReason from HR: %s",
                        request.getEmployee().getFullName(), request.getTotalDays(), request.getStartDate(), request.getEndDate(), rejectionReason)
        );

        return rejected;
    }

    /**
     * Allows an employee to cancel their own pending leave request.
     */
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

        int year = request.getStartDate().getYear();
        LeaveBalance balance = leaveBalanceRepository.findForUpdate(
                request.getEmployee().getId(), request.getLeaveType().getId(), year)
                .orElseThrow(() -> new ResourceNotFoundException("Leave balance not found"));

        // Release hold
        balance.setPendingDays(balance.getPendingDays().subtract(request.getTotalDays()));
        balance.setRemainingDays(balance.getRemainingDays().add(request.getTotalDays()));
        leaveBalanceRepository.save(balance);

        request.setStatus(LeaveStatus.CANCELLED);
        return leaveRequestRepository.save(request);
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

    private BigDecimal calculateWorkingDays(LocalDate start, LocalDate end) {
        long workingDays = 0;
        LocalDate curr = start;
        while (!curr.isAfter(end)) {
            if (curr.getDayOfWeek() != DayOfWeek.SATURDAY && curr.getDayOfWeek() != DayOfWeek.SUNDAY) {
                workingDays++;
            }
            curr = curr.plusDays(1);
        }
        return BigDecimal.valueOf(workingDays);
    }

    private void syncAttendanceToOnLeave(Employee employee, LocalDate start, LocalDate end, String leaveTypeName) {
        LocalDate curr = start;
        while (!curr.isAfter(end)) {
            if (curr.getDayOfWeek() != DayOfWeek.SATURDAY && curr.getDayOfWeek() != DayOfWeek.SUNDAY) {
                final LocalDate day = curr;
                AttendanceRecord record = attendanceRecordRepository.findByEmployeeIdAndWorkDate(employee.getId(), day)
                        .orElseGet(() -> new AttendanceRecord(employee, day, AttendanceStatus.ON_LEAVE, WorkMode.ON_SITE));
                record.setStatus(AttendanceStatus.ON_LEAVE);
                record.setNotes("Approved Leave: " + leaveTypeName);
                attendanceRecordRepository.save(record);
            }
            curr = curr.plusDays(1);
        }
    }
}
