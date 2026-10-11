package com.hrms.security.entity;

/**
 * Reason why a user session ended (Section 5.1).
 */
public enum SessionEndedReason {
    LOGOUT,
    EXPIRED,
    REVOKED
}
