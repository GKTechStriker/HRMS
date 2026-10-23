package com.hrms.leave.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Configurable leave categories (e.g., Annual, Sick, Casual, Unpaid).
 */
@Entity
@Table(name = "leave_types")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class LeaveType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "default_days_per_year", nullable = false, precision = 4, scale = 1)
    private BigDecimal defaultDaysPerYear = BigDecimal.ZERO;

    @Column(name = "is_paid", nullable = false)
    private boolean paid = true;

    @Column(name = "requires_attachment", nullable = false)
    private boolean requiresAttachment = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LeaveType(String code, String name, BigDecimal defaultDaysPerYear, boolean paid) {
        this.code = code;
        this.name = name;
        this.defaultDaysPerYear = defaultDaysPerYear;
        this.paid = paid;
    }
}
