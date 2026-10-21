package com.hrms.employee.entity;

/**
 * Employment status values (Section 5.1).
 */
public enum EmployeeStatus {
    ACTIVE("Active"),
    ON_LEAVE("On Leave"),
    INACTIVE("Inactive");

    private final String displayName;

    EmployeeStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
