package com.hrms.department.repository;

import com.hrms.department.entity.JobRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobRoleRepository extends JpaRepository<JobRole, UUID> {
    List<JobRole> findByDepartmentId(UUID departmentId);
    Optional<JobRole> findByTitleIgnoreCaseAndDepartmentId(String title, UUID departmentId);

    @Query("SELECT jr FROM JobRole jr JOIN FETCH jr.department ORDER BY jr.department.name ASC, jr.level DESC, jr.title ASC")
    List<JobRole> findAllWithDepartment();
}
