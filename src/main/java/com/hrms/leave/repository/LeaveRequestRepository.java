package com.hrms.leave.repository;

import com.hrms.leave.entity.LeaveRequest;
import com.hrms.leave.entity.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

    List<LeaveRequest> findByEmployeeIdOrderByCreatedAtDesc(UUID employeeId);

    @Query("SELECT r FROM LeaveRequest r JOIN FETCH r.employee e JOIN FETCH r.leaveType t " +
           "WHERE r.status = :status ORDER BY r.createdAt ASC")
    List<LeaveRequest> findByStatusWithDetails(@Param("status") LeaveStatus status);

    long countByStatus(LeaveStatus status);

    /**
     * Checks if any active (PENDING or APPROVED) leave request exists overlapping the date range.
     */
    @Query("SELECT COUNT(r) > 0 FROM LeaveRequest r " +
           "WHERE r.employee.id = :employeeId " +
           "AND r.status IN :activeStatuses " +
           "AND r.startDate <= :endDate " +
           "AND r.endDate >= :startDate")
    boolean existsOverlappingLeave(@Param("employeeId") UUID employeeId,
                                  @Param("activeStatuses") Collection<LeaveStatus> activeStatuses,
                                  @Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate);
}
