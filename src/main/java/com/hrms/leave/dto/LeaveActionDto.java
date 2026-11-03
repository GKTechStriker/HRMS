package com.hrms.leave.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Form DTO for HR Admin leave decision (Approve / Reject).
 */
@Getter
@Setter
public class LeaveActionDto {

    @NotNull(message = "Request ID is required")
    private UUID requestId;

    private String rejectionReason;
}
