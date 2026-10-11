package com.hrms.department.repository;

import com.hrms.department.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, UUID> {
    Optional<Department> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCase(String name);

    @Query("SELECT d FROM Department d LEFT JOIN FETCH d.parentDepartment LEFT JOIN FETCH d.headEmployee ORDER BY d.name ASC")
    List<Department> findAllWithRelationsOrderByName();
}
