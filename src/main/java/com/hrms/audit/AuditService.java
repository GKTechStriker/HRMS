package com.hrms.audit;

import com.hrms.audit.entity.AuditLog;
import com.hrms.audit.repository.AuditLogRepository;
import com.hrms.security.CustomUserDetails;
import com.hrms.security.entity.Role;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Audit trail query service and Week 2 verification target (Section 7.2 & 7.4).
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public AuditService(AuditLogRepository auditLogRepository, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    /**
     * Returns recent audit entries scoped to the caller's role:
     * - SUPER_ADMIN sees all entries
     * - HR_ADMIN sees only their own actions (Section 4.2 Permission Matrix)
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public List<AuditLog> getRecentAuditLogsForPrincipal(CustomUserDetails principal, int limit) {
        if (principal.getRole() == Role.SUPER_ADMIN) {
            return auditLogRepository.findRecentWithUser(PageRequest.of(0, limit));
        }
        return auditLogRepository.findByUserIdWithUser(principal.getUserId(), PageRequest.of(0, limit));
    }

    /**
     * Search and paginate audit logs with optional action and date range filters (GAP-07).
     * SUPER_ADMIN sees all records; HR_ADMIN sees only their own actions.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public org.springframework.data.domain.Page<AuditLog> searchAuditLogsPaged(
            CustomUserDetails principal,
            String action,
            java.time.LocalDate fromDate,
            java.time.LocalDate toDate,
            org.springframework.data.domain.Pageable pageable) {
        Instant fromInstant = fromDate != null ? fromDate.atStartOfDay(clock.getZone()).toInstant() : null;
        Instant toInstant = toDate != null ? toDate.plusDays(1).atStartOfDay(clock.getZone()).toInstant().minusNanos(1) : null;
        UUID scopedUserId = (principal != null && principal.getRole() == Role.SUPER_ADMIN) ? null :
                (principal != null ? principal.getUserId() : null);
        String filterAction = (action != null && !action.isBlank()) ? action.trim() : null;

        return auditLogRepository.searchAuditLogs(filterAction, fromInstant, toInstant, scopedUserId, pageable);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public List<String> getDistinctActions() {
        return auditLogRepository.findDistinctActions();
    }

    /**
     * Week 2 Gate Verification Method (Enactment Plan Page 3):
     * "Verify audit table writes actor/session/IP/timestamp on a dummy method"
     */
    @Transactional
    @PreAuthorize("hasRole('HR_ADMIN')")
    @Audited(action = "AUDIT_FOUNDATION_VERIFIED", targetType = "SystemVerification")
    public UUID verifyDummyAuditWrite(String verificationNote) {
        UUID verificationId = UUID.randomUUID();
        AuditContext.setTarget(
                verificationId,
                Map.of("status", "UNVERIFIED"),
                Map.of(
                        "status", "VERIFIED",
                        "note", verificationNote != null ? verificationNote : "Phase 1 Week 2 Audit Gate Check",
                        "verifiedAt", Instant.now(clock).toString()
                )
        );
        return verificationId;
    }
}
