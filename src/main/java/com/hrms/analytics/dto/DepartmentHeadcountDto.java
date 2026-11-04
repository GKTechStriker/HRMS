package com.hrms.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Data transfer object representing headcount aggregated by department.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentHeadcountDto {
    private String departmentName;
    private long count;
}
