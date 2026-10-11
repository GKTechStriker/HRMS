package com.hrms.audit;

import com.hrms.audit.entity.AuditLog;
import com.hrms.audit.repository.AuditLogRepository;
import com.hrms.security.CustomUserDetails;
import com.hrms.security.entity.Role;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
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
                        "verifiedAt", Instant.now().toString()
                )
        );
        return verificationId;
    }
}
