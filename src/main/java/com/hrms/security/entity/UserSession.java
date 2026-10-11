package com.hrms.security.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Companion table capturing session context (IP, User-Agent, login/logout times,
 * revocation details) alongside Spring Session JDBC (Section 5.1).
 */
@Entity
@Table(name = "user_sessions")
public class UserSession {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, unique = true, length = 255)
    private String sessionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "user_agent", nullable = false, columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "login_at", nullable = false)
    private Instant loginAt;

    @Column(name = "logout_at")
    private Instant logoutAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "ended_reason", length = 32)
    private SessionEndedReason endedReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by")
    private User revokedBy;

    public UserSession() {
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.loginAt == null) {
            this.loginAt = Instant.now();
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

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Instant getLoginAt() {
        return loginAt;
    }

    public void setLoginAt(Instant loginAt) {
        this.loginAt = loginAt;
    }

    public Instant getLogoutAt() {
        return logoutAt;
    }

    public void setLogoutAt(Instant logoutAt) {
        this.logoutAt = logoutAt;
    }

    public SessionEndedReason getEndedReason() {
        return endedReason;
    }

    public void setEndedReason(SessionEndedReason endedReason) {
        this.endedReason = endedReason;
    }

    public User getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(User revokedBy) {
        this.revokedBy = revokedBy;
    }
}
