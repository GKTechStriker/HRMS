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

import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Controller exposing tabular CSV report downloads for employees,
 * attendance rosters, and immutable audit logs with streaming response (GAP-07).
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
     * Download full employee directory as CSV via streaming response.
     */
    @GetMapping("/employees/csv")
    public ResponseEntity<StreamingResponseBody> exportEmployeesCsv() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"employees_" + LocalDate.now(clock) + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(exportService::writeEmployeesCsv);
    }

    /**
     * Download attendance records for a given date and optional department as CSV via streaming response.
     */
    @GetMapping("/attendance/csv")
    public ResponseEntity<StreamingResponseBody> exportAttendanceCsv(
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "departmentId", required = false) UUID departmentId) {
        LocalDate targetDate = date != null ? date : LocalDate.now(clock);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"attendance_" + targetDate + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(outputStream -> exportService.writeAttendanceCsv(targetDate, departmentId, outputStream));
    }

    /**
     * Download immutable audit trail as CSV via streaming response with optional action and date filters.
     */
    @GetMapping("/audit/csv")
    public ResponseEntity<StreamingResponseBody> exportAuditCsv(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(name = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit_trail_" + LocalDate.now(clock) + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(outputStream -> exportService.writeAuditCsv(principal, action, fromDate, toDate, outputStream));
    }
}
