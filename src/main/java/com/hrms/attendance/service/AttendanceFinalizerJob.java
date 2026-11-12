package com.hrms.attendance.service;

import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.attendance.entity.WorkMode;
import com.hrms.attendance.repository.AttendanceRecordRepository;
import com.hrms.attendance.repository.HolidayRepository;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.leave.entity.LeaveStatus;
import com.hrms.leave.repository.LeaveRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * Scheduled nightly background job that finalizes daily attendance for active employees.
 * - Detects active employees with missing punches.
 * - If the employee has an approved leave, creates an ON_LEAVE record.
 * - Otherwise, automatically creates an ABSENT record.
 * - Skips weekends and public holidays (exact & recurring).
 */
@Component
public class AttendanceFinalizerJob {

    private static final Logger log = LoggerFactory.getLogger(AttendanceFinalizerJob.class);

    private final AttendanceRecordRepository attendanceRecordRepository;
    private final HolidayRepository holidayRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final Clock clock;

    public AttendanceFinalizerJob(AttendanceRecordRepository attendanceRecordRepository,
                                  HolidayRepository holidayRepository,
                                  LeaveRequestRepository leaveRequestRepository,
                                  Clock clock) {
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.holidayRepository = holidayRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.clock = clock;
    }

    /**
     * Executes at 23:59 every Monday through Friday by default in configured business timezone.
     */
    @Scheduled(cron = "${hrms.attendance.finalizer-cron:0 59 23 * * MON-FRI}", zone = "${hrms.timezone:Asia/Kolkata}")
    @Transactional
    public void finalizeDailyAttendance() {
        LocalDate today = LocalDate.now(clock);
        log.info("Starting nightly attendance finalization for date {}", today);

        // Skip weekends
        if (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            log.info("Skipping attendance finalization: {} is a weekend", today);
            return;
        }

        // Skip exact or recurring holidays
        if (holidayRepository.isPublicHoliday(today)) {
            log.info("Skipping attendance finalization: {} is a registered public holiday", today);
            return;
        }

        List<Employee> missingEmployees = attendanceRecordRepository.findActiveEmployeesWithoutAttendance(today, EmployeeStatus.ACTIVE);
        if (missingEmployees.isEmpty()) {
            log.info("All active employees have attendance records for {}. No finalization needed.", today);
            return;
        }

        int absentCount = 0;
        int onLeaveCount = 0;

        for (Employee emp : missingEmployees) {
            boolean hasApprovedLeave = leaveRequestRepository.existsOverlappingLeave(
                    emp.getId(),
                    List.of(LeaveStatus.APPROVED),
                    today,
                    today
            );

            AttendanceStatus finalStatus = hasApprovedLeave ? AttendanceStatus.ON_LEAVE : AttendanceStatus.ABSENT;
            AttendanceRecord record = new AttendanceRecord(emp, today, finalStatus, WorkMode.ON_SITE);
            record.setNotes(hasApprovedLeave ? "System finalized: Employee on approved leave" : "System finalized: No clock-in detected");

            attendanceRecordRepository.save(record);

            if (hasApprovedLeave) {
                onLeaveCount++;
            } else {
                absentCount++;
            }
        }

        log.info("Nightly attendance finalization completed for {}: Created {} ABSENT and {} ON_LEAVE records.",
                today, absentCount, onLeaveCount);
    }
}
