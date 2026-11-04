package com.hrms.report.service;

import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.repository.AttendanceRecordRepository;
import com.hrms.audit.AuditService;
import com.hrms.audit.entity.AuditLog;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.security.CustomUserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Service providing tabular reporting and RFC-4180 compliant CSV exports
 * for Employee Records, Attendance Rosters, and Audited System Trails.
 */
@Service
@Transactional(readOnly = true)
public class ExportService {

    private final EmployeeRepository employeeRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final AuditService auditService;

    public ExportService(EmployeeRepository employeeRepository,
                         AttendanceRecordRepository attendanceRecordRepository,
                         AuditService auditService) {
        this.employeeRepository = employeeRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.auditService = auditService;
    }

    /**
     * Export all active and archived employees as CSV.
     */
    public byte[] generateEmployeesCsv() {
        List<Employee> employees = employeeRepository.findAllWithDepartmentAndJobRole();
        StringBuilder sb = new StringBuilder();
        sb.append("Employee Code,Full Name,Email,Phone,Department,Job Role,Status,Date of Joining\n");

        for (Employee emp : employees) {
            sb.append(escape(emp.getEmployeeCode())).append(",")
              .append(escape(emp.getFullName())).append(",")
              .append(escape(emp.getUser() != null ? emp.getUser().getEmail() : "")).append(",")
              .append(escape(emp.getPhone())).append(",")
              .append(escape(emp.getDepartment() != null ? emp.getDepartment().getName() : "")).append(",")
              .append(escape(emp.getJobRole() != null ? emp.getJobRole().getTitle() : "")).append(",")
              .append(escape(emp.getStatus() != null ? emp.getStatus().name() : "")).append(",")
              .append(escape(emp.getDateOfJoining() != null ? emp.getDateOfJoining().toString() : ""))
              .append("\n");
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Export attendance roster for the specified date and optional department filter.
     */
    public byte[] generateAttendanceCsv(LocalDate date, UUID departmentId) {
        List<AttendanceRecord> records;
        if (departmentId != null) {
            records = attendanceRecordRepository.findByWorkDateAndDepartmentWithEmployee(date, departmentId);
        } else {
            records = attendanceRecordRepository.findByWorkDateWithEmployee(date);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Date,Employee Code,Employee Name,Department,Work Mode,Clock In,Clock Out,Duration (Minutes),Status,Notes / Correction Reason\n");

        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");

        for (AttendanceRecord rec : records) {
            String clockInStr = rec.getClockIn() != null ? rec.getClockIn().format(timeFmt) : "";
            String clockOutStr = rec.getClockOut() != null ? rec.getClockOut().format(timeFmt) : "";
            String durationStr = rec.getDurationMinutes() != null ? rec.getDurationMinutes().toString() : "";
            String reason = rec.getCorrectionReason() != null ? rec.getCorrectionReason() :
                    (rec.getNotes() != null ? rec.getNotes() : "");

            sb.append(escape(rec.getWorkDate().toString())).append(",")
              .append(escape(rec.getEmployee().getEmployeeCode())).append(",")
              .append(escape(rec.getEmployee().getFullName())).append(",")
              .append(escape(rec.getEmployee().getDepartment() != null ? rec.getEmployee().getDepartment().getName() : "")).append(",")
              .append(escape(rec.getWorkMode() != null ? rec.getWorkMode().name() : "")).append(",")
              .append(escape(clockInStr)).append(",")
              .append(escape(clockOutStr)).append(",")
              .append(escape(durationStr)).append(",")
              .append(escape(rec.getStatus() != null ? rec.getStatus().name() : "")).append(",")
              .append(escape(reason))
              .append("\n");
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Export immutable audit logs as CSV scoped to the caller's authorization level.
     */
    public byte[] generateAuditCsv(CustomUserDetails principal) {
        List<AuditLog> logs = auditService.getRecentAuditLogsForPrincipal(principal, 5000);
        StringBuilder sb = new StringBuilder();
        sb.append("Timestamp,Actor,HR Code,Action,Target Type,Target ID,Session ID,IP Address\n");

        for (AuditLog log : logs) {
            sb.append(escape(log.getOccurredAt() != null ? log.getOccurredAt().toString() : "")).append(",")
              .append(escape(log.getUser() != null ? log.getUser().getEmail() : "")).append(",")
              .append(escape(log.getHrCode() != null ? log.getHrCode() : "")).append(",")
              .append(escape(log.getAction())).append(",")
              .append(escape(log.getTargetType())).append(",")
              .append(escape(log.getTargetId() != null ? log.getTargetId().toString() : "")).append(",")
              .append(escape(log.getSessionId() != null ? log.getSessionId() : "")).append(",")
              .append(escape(log.getIpAddress() != null ? log.getIpAddress() : ""))
              .append("\n");
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * RFC-4180 compliant escaping for CSV cells.
     */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
