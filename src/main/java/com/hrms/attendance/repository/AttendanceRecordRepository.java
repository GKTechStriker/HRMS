package com.hrms.attendance.repository;

import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {

    Optional<AttendanceRecord> findByEmployeeIdAndWorkDate(UUID employeeId, LocalDate workDate);

    Optional<AttendanceRecord> findByEmployeeUserIdAndWorkDate(UUID userId, LocalDate workDate);

    List<AttendanceRecord> findByEmployeeIdAndWorkDateBetweenOrderByWorkDateDesc(
            UUID employeeId, LocalDate startDate, LocalDate endDate);

    @Query("SELECT a FROM AttendanceRecord a JOIN FETCH a.employee e LEFT JOIN FETCH e.department WHERE a.workDate = :workDate ORDER BY e.fullName ASC")
    List<AttendanceRecord> findByWorkDateWithEmployee(@Param("workDate") LocalDate workDate);

    @Query("SELECT a FROM AttendanceRecord a JOIN FETCH a.employee e LEFT JOIN FETCH e.department WHERE a.workDate = :workDate AND e.department.id = :deptId ORDER BY e.fullName ASC")
    List<AttendanceRecord> findByWorkDateAndDepartmentWithEmployee(@Param("workDate") LocalDate workDate, @Param("deptId") UUID deptId);

    long countByWorkDateAndStatus(LocalDate workDate, AttendanceStatus status);

    long countByWorkDate(LocalDate workDate);

    /**
     * Find active employees who have no attendance record for the specified date.
     */
    @Query("SELECT e FROM Employee e WHERE e.status = :activeStatus " +
           "AND NOT EXISTS (SELECT a FROM AttendanceRecord a WHERE a.employee = e AND a.workDate = :workDate)")
    List<Employee> findActiveEmployeesWithoutAttendance(@Param("workDate") LocalDate workDate,
                                                       @Param("activeStatus") EmployeeStatus activeStatus);
}
