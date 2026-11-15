package com.hrms.report.service;

import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.repository.AttendanceRecordRepository;
import com.hrms.audit.AuditService;
import com.hrms.audit.entity.AuditLog;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.security.CustomUserDetails;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            writeEmployeesCsv(baos);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate employees CSV", e);
        }
        return baos.toByteArray();
    }

    public void writeEmployeesCsv(OutputStream outputStream) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        writer.write("Employee Code,Full Name,Email,Phone,Department,Job Role,Status,Date of Joining\n");
        List<Employee> employees = employeeRepository.findAllWithDepartmentAndJobRole();
        for (Employee emp : employees) {
            writer.write(escape(emp.getEmployeeCode()) + ","
                    + escape(emp.getFullName()) + ","
                    + escape(emp.getUser() != null ? emp.getUser().getEmail() : "") + ","
                    + escape(emp.getPhone()) + ","
                    + escape(emp.getDepartment() != null ? emp.getDepartment().getName() : "") + ","
                    + escape(emp.getJobRole() != null ? emp.getJobRole().getTitle() : "") + ","
                    + escape(emp.getStatus() != null ? emp.getStatus().name() : "") + ","
                    + escape(emp.getDateOfJoining() != null ? emp.getDateOfJoining().toString() : "")
                    + "\n");
        }
        writer.flush();
    }

    /**
     * Export attendance roster for the specified date and optional department filter.
     */
    public byte[] generateAttendanceCsv(LocalDate date, UUID departmentId) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            writeAttendanceCsv(date, departmentId, baos);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate attendance CSV", e);
        }
        return baos.toByteArray();
    }

    public void writeAttendanceCsv(LocalDate date, UUID departmentId, OutputStream outputStream) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        writer.write("Date,Employee Code,Employee Name,Department,Work Mode,Clock In,Clock Out,Duration (Minutes),Status,Notes / Correction Reason\n");
        List<AttendanceRecord> records;
        if (departmentId != null) {
            records = attendanceRecordRepository.findByWorkDateAndDepartmentWithEmployee(date, departmentId);
        } else {
            records = attendanceRecordRepository.findByWorkDateWithEmployee(date);
        }
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
        for (AttendanceRecord rec : records) {
            String clockInStr = rec.getClockIn() != null ? rec.getClockIn().format(timeFmt) : "";
            String clockOutStr = rec.getClockOut() != null ? rec.getClockOut().format(timeFmt) : "";
            String durationStr = rec.getDurationMinutes() != null ? rec.getDurationMinutes().toString() : "";
            String reason = rec.getCorrectionReason() != null ? rec.getCorrectionReason() :
                    (rec.getNotes() != null ? rec.getNotes() : "");

            writer.write(escape(rec.getWorkDate().toString()) + ","
                    + escape(rec.getEmployee().getEmployeeCode()) + ","
                    + escape(rec.getEmployee().getFullName()) + ","
                    + escape(rec.getEmployee().getDepartment() != null ? rec.getEmployee().getDepartment().getName() : "") + ","
                    + escape(rec.getWorkMode() != null ? rec.getWorkMode().name() : "") + ","
                    + escape(clockInStr) + ","
                    + escape(clockOutStr) + ","
                    + escape(durationStr) + ","
                    + escape(rec.getStatus() != null ? rec.getStatus().name() : "") + ","
                    + escape(reason)
                    + "\n");
        }
        writer.flush();
    }

    /**
     * Export immutable audit logs as CSV scoped to the caller's authorization level.
     */
    public byte[] generateAuditCsv(CustomUserDetails principal) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            writeAuditCsv(principal, null, null, null, baos);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate audit CSV", e);
        }
        return baos.toByteArray();
    }

    /**
     * Stream immutable audit logs as CSV chunk by chunk with optional filters and no truncation limit (GAP-07).
     */
    public void writeAuditCsv(CustomUserDetails principal, String action, LocalDate fromDate, LocalDate toDate, OutputStream outputStream) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        writer.write("Timestamp,Actor,HR Code,Action,Target Type,Target ID,Session ID,IP Address\n");
        int page = 0;
        int pageSize = 500;
        Page<AuditLog> slice;
        do {
            slice = auditService.searchAuditLogsPaged(principal, action, fromDate, toDate, PageRequest.of(page, pageSize));
            for (AuditLog log : slice.getContent()) {
                writer.write(escape(log.getOccurredAt() != null ? log.getOccurredAt().toString() : "") + ","
                        + escape(log.getUser() != null ? log.getUser().getEmail() : "") + ","
                        + escape(log.getHrCode() != null ? log.getHrCode() : "") + ","
                        + escape(log.getAction()) + ","
                        + escape(log.getTargetType()) + ","
                        + escape(log.getTargetId() != null ? log.getTargetId().toString() : "") + ","
                        + escape(log.getSessionId() != null ? log.getSessionId() : "") + ","
                        + escape(log.getIpAddress() != null ? log.getIpAddress() : "")
                        + "\n");
            }
            writer.flush();
            page++;
        } while (slice.hasNext());
    }

    /**
     * OWASP CSV injection neutralization + RFC-4180 compliant escaping for CSV cells (GAP-04).
     */
    String escape(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value;
        if (startsWithFormulaTrigger(sanitized)) {
            sanitized = "'" + sanitized;
        }
        if (sanitized.contains(",") || sanitized.contains("\"") || sanitized.contains("\n") || sanitized.contains("\r")) {
            return "\"" + sanitized.replace("\"", "\"\"") + "\"";
        }
        return sanitized;
    }

    private static boolean startsWithFormulaTrigger(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        if (isFormulaTriggerChar(first)) {
            return true;
        }
        String leadingStripped = value.stripLeading();
        return !leadingStripped.isEmpty() && isFormulaTriggerChar(leadingStripped.charAt(0));
    }

    private static boolean isFormulaTriggerChar(char ch) {
        return ch == '=' || ch == '+' || ch == '-' || ch == '@' || ch == '\t' || ch == '\r';
    }
}
