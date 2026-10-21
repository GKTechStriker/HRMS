package com.hrms.leave.entity;

import com.hrms.employee.entity.Employee;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Annual quota and running leave balance per employee and leave type.
 * Supports the provisional hold mechanic (pendingDays).
 */
@Entity
@Table(name = "leave_balances",
       uniqueConstraints = @UniqueConstraint(name = "uq_employee_leave_type_year", columnNames = {"employee_id", "leave_type_id", "year"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class LeaveBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(nullable = false)
    private int year;

    @Column(name = "allocated_days", nullable = false, precision = 4, scale = 1)
    private BigDecimal allocatedDays = BigDecimal.ZERO;

    @Column(name = "used_days", nullable = false, precision = 4, scale = 1)
    private BigDecimal usedDays = BigDecimal.ZERO;

    @Column(name = "pending_days", nullable = false, precision = 4, scale = 1)
    private BigDecimal pendingDays = BigDecimal.ZERO;

    @Column(name = "remaining_days", nullable = false, precision = 4, scale = 1)
    private BigDecimal remainingDays = BigDecimal.ZERO;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public LeaveBalance(Employee employee, LeaveType leaveType, int year, BigDecimal allocatedDays) {
        this.employee = employee;
        this.leaveType = leaveType;
        this.year = year;
        this.allocatedDays = allocatedDays;
        this.usedDays = BigDecimal.ZERO;
        this.pendingDays = BigDecimal.ZERO;
        this.remainingDays = allocatedDays;
    }
}
