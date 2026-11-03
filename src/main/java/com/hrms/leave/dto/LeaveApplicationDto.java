package com.hrms.leave.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Form DTO for employee leave submission.
 */
@Getter
@Setter
public class LeaveApplicationDto {

    @NotNull(message = "Leave type is required")
    private UUID leaveTypeId;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    @NotNull(message = "End date is required")
    private LocalDate endDate;

    @NotBlank(message = "Reason for leave is required")
    private String reason;
}
