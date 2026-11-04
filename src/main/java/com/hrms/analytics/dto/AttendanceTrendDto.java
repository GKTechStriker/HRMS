package com.hrms.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Data transfer object representing daily attendance status counts.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceTrendDto {
    private LocalDate date;
    private long present;
    private long late;
    private long halfDay;
    private long absent;
    private long onLeave;
    private long holiday;
}
