package com.hrms.analytics.controller;

import com.hrms.analytics.dto.AttendanceTrendDto;
import com.hrms.analytics.dto.DepartmentHeadcountDto;
import com.hrms.analytics.dto.LeaveUsageDto;
import com.hrms.analytics.service.AnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API Controller serving aggregated analytical datasets
 * for Chart.js interactive visualizations on the management dashboard.
 */
@RestController
@RequestMapping("/api/analytics")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
public class AnalyticsApiController {

    private final AnalyticsService analyticsService;

    public AnalyticsApiController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * Headcount distribution by department for active employees.
     */
    @GetMapping("/headcount-by-dept")
    public ResponseEntity<List<DepartmentHeadcountDto>> getHeadcountByDept() {
        return ResponseEntity.ok(analyticsService.getDepartmentHeadcount());
    }

    /**
     * Daily attendance time-series trends over the requested window.
     */
    @GetMapping("/attendance-trends")
    public ResponseEntity<List<AttendanceTrendDto>> getAttendanceTrends(
            @RequestParam(name = "days", defaultValue = "14") int days) {
        return ResponseEntity.ok(analyticsService.getAttendanceTrends(days));
    }

    /**
     * Cumulative approved leave days consumed grouped by leave category.
     */
    @GetMapping("/leave-usage")
    public ResponseEntity<List<LeaveUsageDto>> getLeaveUsage() {
        return ResponseEntity.ok(analyticsService.getLeaveUsage());
    }
}
