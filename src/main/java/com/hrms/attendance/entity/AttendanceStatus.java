package com.hrms.attendance.entity;

/**
 * Attendance status for an employee's workday.
 */
public enum AttendanceStatus {
    PRESENT("Present"),
    LATE("Late Arrival"),
    HALF_DAY("Half Day"),
    ABSENT("Absent"),
    ON_LEAVE("On Approved Leave"),
    HOLIDAY("Public Holiday");

    private final String displayName;

    AttendanceStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
