package com.hrms.audit.entity;

import com.hrms.security.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit log entry (Section 5.1 & Section 7).
 * Marked @Immutable at Hibernate level and protected by database trigger on audit_log.
 */
@Entity
@Table(name = "audit_log")
@Immutable
public class AuditLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false, length = 255)
    private String sessionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "hr_code", updatable = false, length = 20)
    private String hrCode;

    @Column(name = "action", nullable = false, updatable = false, length = 100)
    private String action;

    @Column(name = "target_type", nullable = false, updatable = false, length = 100)
    private String targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous_data", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> previousData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_data", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> newData;

    @Column(name = "ip_address", nullable = false, updatable = false, length = 45)
    private String ipAddress;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public AuditLog() {
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.occurredAt == null) {
            this.occurredAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getHrCode() {
        return hrCode;
    }

    public void setHrCode(String hrCode) {
        this.hrCode = hrCode;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public void setTargetId(UUID targetId) {
        this.targetId = targetId;
    }

    public Map<String, Object> getPreviousData() {
        return previousData;
    }

    public void setPreviousData(Map<String, Object> previousData) {
        this.previousData = previousData;
    }

    public Map<String, Object> getNewData() {
        return newData;
    }

    public void setNewData(Map<String, Object> newData) {
        this.newData = newData;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
