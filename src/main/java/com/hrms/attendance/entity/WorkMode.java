package com.hrms.attendance.entity;

/**
 * Work mode recorded on clock-in.
 */
public enum WorkMode {
    ON_SITE("On-Site"),
    REMOTE("Remote / WFH");

    private final String displayName;

    WorkMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
