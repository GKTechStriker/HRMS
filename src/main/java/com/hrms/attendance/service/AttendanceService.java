package com.hrms.attendance.service;

import com.hrms.attendance.dto.AttendanceCorrectionDto;
import com.hrms.attendance.dto.DailyAttendanceSummaryDto;
import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.attendance.entity.WorkMode;
import com.hrms.attendance.repository.AttendanceRecordRepository;
import com.hrms.attendance.repository.HolidayRepository;
import com.hrms.audit.Audited;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.exception.BusinessException;
import com.hrms.exception.ResourceNotFoundException;
import com.hrms.security.entity.User;
import com.hrms.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Core business service handling employee clock-in/out,
 * daily calculations, status evaluation, and audited HR corrections.
 */
@Service
public class AttendanceService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceService.class);

    private static final LocalTime SHIFT_START_TIME = LocalTime.of(9, 0);
    private static final LocalTime LATE_THRESHOLD_TIME = LocalTime.of(9, 15);
    private static final int HALF_DAY_MINUTES_THRESHOLD = 240; // 4 hours

    private final AttendanceRecordRepository attendanceRecordRepository;
    private final EmployeeRepository employeeRepository;
    private final HolidayRepository holidayRepository;
    private final UserRepository userRepository;

    public AttendanceService(AttendanceRecordRepository attendanceRecordRepository,
                             EmployeeRepository employeeRepository,
                             HolidayRepository holidayRepository,
                             UserRepository userRepository) {
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.employeeRepository = employeeRepository;
        this.holidayRepository = holidayRepository;
        this.userRepository = userRepository;
    }

    /**
     * Retrieves today's attendance record for the given user, if any.
     */
    @Transactional(readOnly = true)
    public Optional<AttendanceRecord> getTodayRecord(UUID userId) {
        return attendanceRecordRepository.findByEmployeeUserIdAndWorkDate(userId, LocalDate.now());
    }

    /**
     * Executes clock-in for the authenticated user.
     */
    @Transactional
    public AttendanceRecord clockIn(UUID userId, WorkMode workMode, String ipAddress, String notes) {
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("No employee profile is linked to your user account. Only employees can clock in."));

        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new BusinessException("Cannot clock in: Your employment status is " + employee.getStatus().getDisplayName());
        }

        LocalDate today = LocalDate.now();
        Optional<AttendanceRecord> existingOpt = attendanceRecordRepository.findByEmployeeIdAndWorkDate(employee.getId(), today);

        if (existingOpt.isPresent()) {
            AttendanceRecord existing = existingOpt.get();
            if (existing.getClockIn() != null) {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("hh:mm a");
                throw new BusinessException("You have already clocked in today at " + existing.getClockIn().format(fmt));
            }
        }

        OffsetDateTime now = OffsetDateTime.now();
        LocalTime localTime = now.toLocalTime();

        // Determine if arriving late (after 09:15 AM)
        AttendanceStatus initialStatus = AttendanceStatus.PRESENT;
        if (localTime.isAfter(LATE_THRESHOLD_TIME)) {
            initialStatus = AttendanceStatus.LATE;
        }

        AttendanceRecord record = existingOpt.orElseGet(() -> new AttendanceRecord(employee, today, initialStatus, workMode));
        record.setStatus(initialStatus);
        record.setWorkMode(workMode != null ? workMode : WorkMode.ON_SITE);
        record.setClockIn(now);
        record.setClockInIp(ipAddress);
        if (notes != null && !notes.isBlank()) {
            record.setNotes(notes.trim());
        }

        log.info("Employee {} ({}) clocked in at {} with status {}", employee.getFullName(), employee.getEmployeeCode(), now, initialStatus);
        return attendanceRecordRepository.save(record);
    }

    /**
     * Executes clock-out for the authenticated user and calculates total work duration.
     */
    @Transactional
    public AttendanceRecord clockOut(UUID userId, String ipAddress, String notes) {
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("No employee profile is linked to your user account."));

        LocalDate today = LocalDate.now();
        AttendanceRecord record = attendanceRecordRepository.findByEmployeeIdAndWorkDate(employee.getId(), today)
                .orElseThrow(() -> new BusinessException("No clock-in record found for today. Please clock in first."));

        if (record.getClockIn() == null) {
            throw new BusinessException("No clock-in timestamp recorded for today. Please clock in first.");
        }

        if (record.getClockOut() != null) {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("hh:mm a");
            throw new BusinessException("You have already clocked out today at " + record.getClockOut().format(fmt));
        }

        OffsetDateTime now = OffsetDateTime.now();
        record.setClockOut(now);
        record.setClockOutIp(ipAddress);

        // Calculate duration
        long minutes = Duration.between(record.getClockIn(), now).toMinutes();
        record.setDurationMinutes((int) minutes);

        // Check if worked duration is less than half day (4 hours) and wasn't already marked late/half-day
        if (minutes < HALF_DAY_MINUTES_THRESHOLD && record.getStatus() == AttendanceStatus.PRESENT) {
            record.setStatus(AttendanceStatus.HALF_DAY);
        }

        if (notes != null && !notes.isBlank()) {
            String combined = record.getNotes() != null ? record.getNotes() + " | Clock-out note: " + notes.trim() : notes.trim();
            record.setNotes(combined);
        }

        log.info("Employee {} ({}) clocked out at {}. Duration: {} minutes, Status: {}",
                employee.getFullName(), employee.getEmployeeCode(), now, minutes, record.getStatus());
        return attendanceRecordRepository.save(record);
    }

    /**
     * Fetches monthly attendance history for an employee.
     */
    @Transactional(readOnly = true)
    public List<AttendanceRecord> getEmployeeMonthlyAttendance(UUID employeeId, int year, int month) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.plusMonths(1).minusDays(1);
        return attendanceRecordRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateDesc(employeeId, start, end);
    }

    /**
     * Fetches daily attendance roster for HR management with optional department filter.
     */
    @Transactional(readOnly = true)
    public List<AttendanceRecord> getDailyAttendance(LocalDate date, UUID departmentId) {
        if (departmentId != null) {
            return attendanceRecordRepository.findByWorkDateAndDepartmentWithEmployee(date, departmentId);
        }
        return attendanceRecordRepository.findByWorkDateWithEmployee(date);
    }

    /**
     * Calculates daily statistics summary for HR dashboard view.
     */
    @Transactional(readOnly = true)
    public DailyAttendanceSummaryDto getDailySummary(LocalDate date) {
        long activeEmployees = employeeRepository.countByStatus(EmployeeStatus.ACTIVE);
        long present = attendanceRecordRepository.countByWorkDateAndStatus(date, AttendanceStatus.PRESENT);
        long late = attendanceRecordRepository.countByWorkDateAndStatus(date, AttendanceStatus.LATE);
        long halfDay = attendanceRecordRepository.countByWorkDateAndStatus(date, AttendanceStatus.HALF_DAY);
        long absent = attendanceRecordRepository.countByWorkDateAndStatus(date, AttendanceStatus.ABSENT);
        long onLeave = attendanceRecordRepository.countByWorkDateAndStatus(date, AttendanceStatus.ON_LEAVE);
        long holiday = attendanceRecordRepository.countByWorkDateAndStatus(date, AttendanceStatus.HOLIDAY);

        return DailyAttendanceSummaryDto.builder()
                .date(date)
                .totalActiveEmployees(activeEmployees)
                .presentCount(present)
                .lateCount(late)
                .halfDayCount(halfDay)
                .absentCount(absent)
                .onLeaveCount(onLeave)
                .holidayCount(holiday)
                .build();
    }

    /**
     * HR Manual Correction of an attendance record.
     * Strictly audited to record before/after states and the mandatory reason in the immutable audit log.
     */
    @Audited(action = "CORRECT_ATTENDANCE", targetType = "ATTENDANCE_RECORD")
    @Transactional
    public AttendanceRecord correctAttendance(AttendanceCorrectionDto dto, UUID hrUserId) {
        User hrUser = userRepository.findById(hrUserId)
                .orElseThrow(() -> new ResourceNotFoundException("HR User not found"));

        Employee employee = employeeRepository.findById(dto.getEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with ID: " + dto.getEmployeeId()));

        AttendanceRecord record;
        if (dto.getRecordId() != null) {
            record = attendanceRecordRepository.findById(dto.getRecordId())
                    .orElseThrow(() -> new ResourceNotFoundException("Attendance record not found"));
        } else {
            record = attendanceRecordRepository.findByEmployeeIdAndWorkDate(employee.getId(), dto.getWorkDate())
                    .orElseGet(() -> new AttendanceRecord(employee, dto.getWorkDate(), dto.getStatus(), dto.getWorkMode()));
        }

        record.setStatus(dto.getStatus());
        record.setWorkMode(dto.getWorkMode() != null ? dto.getWorkMode() : WorkMode.ON_SITE);
        record.setManuallyCorrected(true);
        record.setCorrectedBy(hrUser);
        record.setCorrectionReason(dto.getReason().trim());

        ZoneOffset offset = OffsetDateTime.now().getOffset();

        if (dto.getClockInTime() != null && !dto.getClockInTime().isBlank()) {
            LocalTime inTime = LocalTime.parse(dto.getClockInTime().trim());
            record.setClockIn(OffsetDateTime.of(dto.getWorkDate(), inTime, offset));
        }

        if (dto.getClockOutTime() != null && !dto.getClockOutTime().isBlank()) {
            LocalTime outTime = LocalTime.parse(dto.getClockOutTime().trim());
            record.setClockOut(OffsetDateTime.of(dto.getWorkDate(), outTime, offset));
        }

        if (record.getClockIn() != null && record.getClockOut() != null) {
            long minutes = Duration.between(record.getClockIn(), record.getClockOut()).toMinutes();
            record.setDurationMinutes((int) Math.max(0, minutes));
        }

        log.info("HR Admin {} manually corrected attendance for employee {} on date {}. Reason: {}",
                hrUser.getEmail(), employee.getFullName(), dto.getWorkDate(), dto.getReason());

        return attendanceRecordRepository.save(record);
    }
}
