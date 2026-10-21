package com.hrms.attendance.entity;

import com.hrms.employee.entity.Employee;
import com.hrms.security.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Daily employee attendance record tracking punch timestamps, work mode,
 * calculated duration, and any audited manual corrections.
 */
@Entity
@Table(name = "attendance_records",
       uniqueConstraints = @UniqueConstraint(name = "uq_employee_work_date", columnNames = {"employee_id", "work_date"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class AttendanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "clock_in")
    private OffsetDateTime clockIn;

    @Column(name = "clock_out")
    private OffsetDateTime clockOut;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AttendanceStatus status = AttendanceStatus.PRESENT;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_mode", nullable = false, length = 32)
    private WorkMode workMode = WorkMode.ON_SITE;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "clock_in_ip", length = 45)
    private String clockInIp;

    @Column(name = "clock_out_ip", length = 45)
    private String clockOutIp;

    @Column(name = "is_manually_corrected", nullable = false)
    private boolean manuallyCorrected = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corrected_by")
    private User correctedBy;

    @Column(name = "correction_reason", columnDefinition = "TEXT")
    private String correctionReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AttendanceRecord(Employee employee, LocalDate workDate, AttendanceStatus status, WorkMode workMode) {
        this.employee = employee;
        this.workDate = workDate;
        this.status = status;
        this.workMode = workMode;
    }
}
