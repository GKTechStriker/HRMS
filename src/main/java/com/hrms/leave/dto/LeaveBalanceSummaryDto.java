package com.hrms.leave.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Summary card DTO displaying allocated, used, pending, and remaining days for a leave type.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaveBalanceSummaryDto {
    private UUID balanceId;
    private UUID leaveTypeId;
    private String leaveTypeCode;
    private String leaveTypeName;
    private boolean isPaid;
    private BigDecimal allocatedDays;
    private BigDecimal usedDays;
    private BigDecimal pendingDays;
    private BigDecimal remainingDays;
}
