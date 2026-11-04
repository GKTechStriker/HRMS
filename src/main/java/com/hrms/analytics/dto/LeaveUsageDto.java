package com.hrms.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Data transfer object representing cumulative leave days taken grouped by leave type.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LeaveUsageDto {
    private String leaveType;
    private BigDecimal daysTaken;
}
