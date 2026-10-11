package com.hrms.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative audit annotation applied to service-layer write operations (Section 7.2).
 * Intercepted by AuditAspect to record actor, session ID, IP address, and before/after state.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
    /**
     * Action identifier, e.g., "EMPLOYEE_CREATED", "LEAVE_APPROVED", "SESSION_REVOKED".
     */
    String action();

    /**
     * Target entity type name, e.g., "Employee", "LeaveRequest", "UserSession".
     */
    String targetType();
}
