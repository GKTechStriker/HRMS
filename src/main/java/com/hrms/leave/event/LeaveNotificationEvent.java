package com.hrms.leave.event;

/**
 * Domain event published when a leave request lifecycle email should be dispatched
 * once the originating database transaction commits.
 */
public record LeaveNotificationEvent(String to, String subject, String body) {
}
