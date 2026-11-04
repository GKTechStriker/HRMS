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

    @Query("SELECT r FROM LeaveRequest r JOIN FETCH r.leaveType t " +
           "WHERE r.employee.id = :employeeId ORDER BY r.createdAt DESC")
    List<LeaveRequest> findByEmployeeIdWithLeaveTypeOrderByCreatedAtDesc(@Param("employeeId") UUID employeeId);

    @Query("SELECT r FROM LeaveRequest r JOIN FETCH r.employee e JOIN FETCH r.leaveType t LEFT JOIN FETCH r.actionedBy a " +
           "WHERE r.status = :status ORDER BY r.createdAt ASC")
    List<LeaveRequest> findByStatusWithDetails(@Param("status") LeaveStatus status);

    @Query("SELECT r FROM LeaveRequest r JOIN FETCH r.employee e JOIN FETCH r.leaveType t LEFT JOIN FETCH r.actionedBy a " +
           "ORDER BY r.createdAt DESC")
    List<LeaveRequest> findAllWithDetails();

    long countByStatus(LeaveStatus status);

    @Query("SELECT r.leaveType.name, COALESCE(SUM(r.totalDays), 0) FROM LeaveRequest r WHERE r.status = :status GROUP BY r.leaveType.name")
    List<Object[]> sumDaysByLeaveType(@Param("status") LeaveStatus status);

    /**
     * Counts active (PENDING or APPROVED) leave requests overlapping the specified date range.
     */
    @Query("SELECT COUNT(r) FROM LeaveRequest r " +
           "WHERE r.employee.id = :employeeId " +
           "AND r.status IN :activeStatuses " +
           "AND r.startDate <= :endDate " +
           "AND r.endDate >= :startDate")
    long countOverlappingLeaves(@Param("employeeId") UUID employeeId,
                                @Param("activeStatuses") Collection<LeaveStatus> activeStatuses,
                                @Param("startDate") LocalDate startDate,
                                @Param("endDate") LocalDate endDate);

    default boolean existsOverlappingLeave(UUID employeeId,
                                          Collection<LeaveStatus> activeStatuses,
                                          LocalDate startDate,
                                          LocalDate endDate) {
        return countOverlappingLeaves(employeeId, activeStatuses, startDate, endDate) > 0;
    }
}
