package com.hrms.attendance.dto;

import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.attendance.entity.WorkMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * DTO for HR Admin manual attendance corrections.
 * Reason is required to maintain complete accountability.
 */
@Getter
@Setter
public class AttendanceCorrectionDto {

    private UUID recordId;

    @NotNull(message = "Employee ID is required")
    private UUID employeeId;

    @NotNull(message = "Work date is required")
    private LocalDate workDate;

    @NotNull(message = "Attendance status is required")
    private AttendanceStatus status;

    private WorkMode workMode = WorkMode.ON_SITE;

    private String clockInTime;   // Format: HH:mm

    private String clockOutTime;  // Format: HH:mm

    @NotBlank(message = "Correction reason is mandatory for audit logging")
    private String reason;
}
