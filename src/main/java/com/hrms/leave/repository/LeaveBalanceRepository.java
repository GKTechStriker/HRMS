package com.hrms.leave.repository;

import com.hrms.leave.entity.LeaveBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, UUID> {

    List<LeaveBalance> findByEmployeeIdAndYear(UUID employeeId, int year);

    Optional<LeaveBalance> findByEmployeeIdAndLeaveTypeIdAndYear(UUID employeeId, UUID leaveTypeId, int year);

    /**
     * Pessimistic locking to serialize concurrent leave allocations/holds.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM LeaveBalance b WHERE b.employee.id = :employeeId AND b.leaveType.id = :leaveTypeId AND b.year = :year")
    Optional<LeaveBalance> findForUpdate(@Param("employeeId") UUID employeeId,
                                        @Param("leaveTypeId") UUID leaveTypeId,
                                        @Param("year") int year);
}
