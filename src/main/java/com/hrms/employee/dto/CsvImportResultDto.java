package com.hrms.employee.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Report DTO returned by CSV bulk import (supports both Dry-Run and Commit modes).
 */
public record CsvImportResultDto(
        boolean dryRun,
        boolean committed,
        int totalRows,
        int validRows,
        int errorCount,
        List<RowPreview> rows,
        List<String> errors
) {
    public record RowPreview(
            int lineNumber,
            String fullName,
            String email,
            String phone,
            String departmentName,
            String jobRoleTitle,
            LocalDate dateOfBirth,
            LocalDate dateOfJoining,
            String systemRole,
            boolean valid,
            String validationMessage
    ) {
    }
}
