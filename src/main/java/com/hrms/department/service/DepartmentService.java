package com.hrms.department.service;

import com.hrms.audit.AuditContext;
import com.hrms.audit.Audited;
import com.hrms.department.entity.Department;
import com.hrms.department.entity.JobRole;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.department.repository.JobRoleRepository;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.exception.BusinessException;
import com.hrms.exception.ResourceNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service managing Departments, nested organizational structure, and Job Roles (Section 6.5).
 */
@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final JobRoleRepository jobRoleRepository;
    private final EmployeeRepository employeeRepository;

    public DepartmentService(DepartmentRepository departmentRepository,
                             JobRoleRepository jobRoleRepository,
                             EmployeeRepository employeeRepository) {
        this.departmentRepository = departmentRepository;
        this.jobRoleRepository = jobRoleRepository;
        this.employeeRepository = employeeRepository;
    }

    @Transactional(readOnly = true)
    public List<Department> getAllDepartments() {
        return departmentRepository.findAllWithRelationsOrderByName();
    }

    @Transactional(readOnly = true)
    public List<JobRole> getAllJobRoles() {
        return jobRoleRepository.findAllWithDepartment();
    }

    @Transactional(readOnly = true)
    public Map<UUID, Long> getDepartmentHeadcounts() {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        for (Department dept : departmentRepository.findAll()) {
            counts.put(dept.getId(), employeeRepository.countByDepartmentId(dept.getId()));
        }
        return counts;
    }

    @Transactional
    @PreAuthorize("hasRole('HR_ADMIN')")
    @Audited(action = "DEPARTMENT_CREATED", targetType = "Department")
    public Department createDepartment(String name, UUID parentDepartmentId, UUID headEmployeeId) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("Department name is required.");
        }
        String trimmedName = name.trim();
        if (departmentRepository.existsByNameIgnoreCase(trimmedName)) {
            throw new BusinessException("Department already exists: " + trimmedName);
        }

        Department dept = new Department();
        dept.setName(trimmedName);

        if (parentDepartmentId != null) {
            Department parent = departmentRepository.findById(parentDepartmentId)
                    .orElseThrow(() -> ResourceNotFoundException.forEntity("Department", parentDepartmentId.toString()));
            dept.setParentDepartment(parent);
        }

        if (headEmployeeId != null) {
            Employee head = employeeRepository.findById(headEmployeeId)
                    .orElseThrow(() -> ResourceNotFoundException.forEntity("Employee", headEmployeeId.toString()));
            dept.setHeadEmployee(head);
        }

        dept = departmentRepository.save(dept);

        Map<String, Object> newData = new LinkedHashMap<>();
        newData.put("name", dept.getName());
        newData.put("parentDepartment", dept.getParentDepartment() != null ? dept.getParentDepartment().getName() : null);
        newData.put("headEmployee", dept.getHeadEmployee() != null ? dept.getHeadEmployee().getEmployeeCode() : null);

        AuditContext.setTarget(dept.getId(), null, newData);
        return dept;
    }

    @Transactional
    @PreAuthorize("hasRole('HR_ADMIN')")
    @Audited(action = "JOB_ROLE_CREATED", targetType = "JobRole")
    public JobRole createJobRole(String title, int level, UUID departmentId) {
        if (title == null || title.isBlank()) {
            throw new BusinessException("Job role title is required.");
        }
        if (departmentId == null) {
            throw new BusinessException("Department is required for a job role.");
        }

        Department dept = departmentRepository.findById(departmentId)
                .orElseThrow(() -> ResourceNotFoundException.forEntity("Department", departmentId.toString()));

        String trimmedTitle = title.trim();
        if (jobRoleRepository.findByTitleIgnoreCaseAndDepartmentId(trimmedTitle, departmentId).isPresent()) {
            throw new BusinessException("Job role '" + trimmedTitle + "' already exists in " + dept.getName());
        }

        JobRole role = new JobRole();
        role.setTitle(trimmedTitle);
        role.setLevel(Math.max(1, level));
        role.setDepartment(dept);
        role = jobRoleRepository.save(role);

        Map<String, Object> newData = Map.of(
                "title", role.getTitle(),
                "level", role.getLevel(),
                "department", dept.getName()
        );
        AuditContext.setTarget(role.getId(), null, newData);
        return role;
    }
}
