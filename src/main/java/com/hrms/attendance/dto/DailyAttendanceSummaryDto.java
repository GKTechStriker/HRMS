package com.hrms.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Metric summary of attendance for a given calendar date.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyAttendanceSummaryDto {
    private LocalDate date;
    private long totalActiveEmployees;
    private long presentCount;
    private long lateCount;
    private long halfDayCount;
    private long absentCount;
    private long onLeaveCount;
    private long holidayCount;
}
