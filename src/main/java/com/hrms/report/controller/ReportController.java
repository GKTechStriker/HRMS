package com.hrms.report.controller;

import com.hrms.report.service.ExportService;
import com.hrms.security.CustomUserDetails;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Controller exposing tabular CSV report downloads for employees,
 * attendance rosters, and immutable audit logs.
 */
@Controller
@RequestMapping("/reports")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
public class ReportController {

    private final ExportService exportService;
    private final Clock clock;

    public ReportController(ExportService exportService, Clock clock) {
        this.exportService = exportService;
        this.clock = clock;
    }

    /**
     * Download full employee directory as CSV.
     */
    @GetMapping("/employees/csv")
    public ResponseEntity<byte[]> exportEmployeesCsv() {
        byte[] csv = exportService.generateEmployeesCsv();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"employees_" + LocalDate.now(clock) + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    /**
     * Download attendance records for a given date and optional department as CSV.
     */
    @GetMapping("/attendance/csv")
    public ResponseEntity<byte[]> exportAttendanceCsv(
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "departmentId", required = false) UUID departmentId) {
        LocalDate targetDate = date != null ? date : LocalDate.now(clock);
        byte[] csv = exportService.generateAttendanceCsv(targetDate, departmentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"attendance_" + targetDate + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    /**
     * Download immutable audit trail as CSV (scoped to caller role).
     */
    @GetMapping("/audit/csv")
    public ResponseEntity<byte[]> exportAuditCsv(@AuthenticationPrincipal CustomUserDetails principal) {
        byte[] csv = exportService.generateAuditCsv(principal);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit_trail_" + LocalDate.now(clock) + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }
}
