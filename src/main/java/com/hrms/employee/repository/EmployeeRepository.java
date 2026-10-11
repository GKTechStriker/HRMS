package com.hrms.employee.repository;

import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
    Optional<Employee> findByEmployeeCode(String employeeCode);
    boolean existsByEmployeeCode(String employeeCode);

    @Query("SELECT e FROM Employee e JOIN FETCH e.department JOIN FETCH e.jobRole LEFT JOIN FETCH e.user LEFT JOIN FETCH e.reportsTo WHERE e.id = :id")
    Optional<Employee> findByIdWithDetails(@Param("id") UUID id);

    @Query("SELECT e FROM Employee e JOIN FETCH e.department JOIN FETCH e.jobRole LEFT JOIN FETCH e.user WHERE e.user.id = :userId")
    Optional<Employee> findByUserId(@Param("userId") UUID userId);

    List<Employee> findByStatus(EmployeeStatus status);
    long countByStatus(EmployeeStatus status);
    long countByDepartmentId(UUID departmentId);

    @Query("SELECT e FROM Employee e JOIN FETCH e.department JOIN FETCH e.jobRole LEFT JOIN FETCH e.user ORDER BY e.employeeCode ASC")
    List<Employee> findAllWithDepartmentAndJobRole();

    @Query(
        value = """
            SELECT e FROM Employee e
            JOIN FETCH e.department d
            JOIN FETCH e.jobRole jr
            LEFT JOIN FETCH e.user u
            WHERE (:search IS NULL OR :search = ''
                   OR LOWER(e.fullName) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(e.employeeCode) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR (u IS NOT NULL AND LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))))
              AND (:departmentId IS NULL OR d.id = :departmentId)
              AND (:jobRoleId IS NULL OR jr.id = :jobRoleId)
              AND (:status IS NULL OR e.status = :status)
            """,
        countQuery = """
            SELECT COUNT(e) FROM Employee e
            JOIN e.department d
            JOIN e.jobRole jr
            LEFT JOIN e.user u
            WHERE (:search IS NULL OR :search = ''
                   OR LOWER(e.fullName) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(e.employeeCode) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR (u IS NOT NULL AND LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))))
              AND (:departmentId IS NULL OR d.id = :departmentId)
              AND (:jobRoleId IS NULL OR jr.id = :jobRoleId)
              AND (:status IS NULL OR e.status = :status)
            """
    )
    Page<Employee> searchDirectory(@Param("search") String search,
                                   @Param("departmentId") UUID departmentId,
                                   @Param("jobRoleId") UUID jobRoleId,
                                   @Param("status") EmployeeStatus status,
                                   Pageable pageable);
}
