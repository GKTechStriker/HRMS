package com.hrms.employee.service;

import com.hrms.department.entity.Department;
import com.hrms.department.entity.JobRole;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.department.repository.JobRoleRepository;
import com.hrms.department.service.DepartmentService;
import com.hrms.employee.dto.EmployeeFormDto;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 Regression Tests covering GAP-02:
 * - Department <-> JobRole affinity validation on employee create & update
 * - Self-reporting and circular reporting hierarchy prevention
 * - Circular department parent hierarchy prevention
 * - Employee form client-side data-dept-id attribute rendering
 */
@SpringBootTest
class Phase3IntegrityAndSecurityTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private DepartmentService departmentService;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private JobRoleRepository jobRoleRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("GAP-02: Employee create and update reject a JobRole belonging to a different Department")
    @WithUserDetails("hr1@company.com")
    void createAndUpdateEmployeeRejectMismatchedDepartmentAndJobRole() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        Department deptA = departmentService.createDepartment("QA-DeptA-" + suffix, null, null);
        Department deptB = departmentService.createDepartment("QA-DeptB-" + suffix, null, null);
        JobRole roleA = departmentService.createJobRole("RoleA-" + suffix, 2, deptA.getId());
        JobRole roleB = departmentService.createJobRole("RoleB-" + suffix, 2, deptB.getId());

        Employee createdEmp = null;
        try {
            // Create with Dept A + Role B must fail
            EmployeeFormDto mismatchCreate = new EmployeeFormDto();
            mismatchCreate.setFullName("Mismatch Create User");
            mismatchCreate.setDepartmentId(deptA.getId());
            mismatchCreate.setJobRoleId(roleB.getId());
            mismatchCreate.setDateOfJoining(LocalDate.of(2026, 1, 15));
            mismatchCreate.setStatus(EmployeeStatus.ACTIVE);

            BusinessException createEx = assertThrows(BusinessException.class,
                    () -> employeeService.createEmployee(mismatchCreate));
            assertTrue(createEx.getMessage().contains("belongs to department"));

            // Create valid employee with Dept A + Role A
            EmployeeFormDto validCreate = new EmployeeFormDto();
            validCreate.setFullName("Valid Affinity User");
            validCreate.setDepartmentId(deptA.getId());
            validCreate.setJobRoleId(roleA.getId());
            validCreate.setDateOfJoining(LocalDate.of(2026, 1, 15));
            validCreate.setStatus(EmployeeStatus.ACTIVE);
            createdEmp = employeeService.createEmployee(validCreate);

            // Update with Dept A + Role B must fail
            final UUID empId = createdEmp.getId();
            EmployeeFormDto mismatchUpdate = new EmployeeFormDto();
            mismatchUpdate.setFullName("Valid Affinity User");
            mismatchUpdate.setDepartmentId(deptA.getId());
            mismatchUpdate.setJobRoleId(roleB.getId());
            mismatchUpdate.setDateOfJoining(LocalDate.of(2026, 1, 15));
            mismatchUpdate.setStatus(EmployeeStatus.ACTIVE);

            BusinessException updateEx = assertThrows(BusinessException.class,
                    () -> employeeService.updateEmployee(empId, mismatchUpdate));
            assertTrue(updateEx.getMessage().contains("belongs to department"));
        } finally {
            if (createdEmp != null) {
                employeeRepository.deleteById(createdEmp.getId());
            }
            jobRoleRepository.deleteById(roleB.getId());
            jobRoleRepository.deleteById(roleA.getId());
            departmentRepository.deleteById(deptB.getId());
            departmentRepository.deleteById(deptA.getId());
        }
    }

    @Test
    @DisplayName("GAP-02: Employee reporting hierarchy rejects self-reporting and circular manager chains")
    @WithUserDetails("hr1@company.com")
    void updateEmployeeRejectsSelfAndCircularReportingHierarchy() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        Department dept = departmentService.createDepartment("HierDept-" + suffix, null, null);
        JobRole role = departmentService.createJobRole("HierRole-" + suffix, 2, dept.getId());

        Employee empA = null;
        Employee empB = null;
        try {
            EmployeeFormDto dtoA = new EmployeeFormDto();
            dtoA.setFullName("Hierarchy User A");
            dtoA.setDepartmentId(dept.getId());
            dtoA.setJobRoleId(role.getId());
            dtoA.setDateOfJoining(LocalDate.of(2026, 1, 10));
            dtoA.setStatus(EmployeeStatus.ACTIVE);
            empA = employeeService.createEmployee(dtoA);

            EmployeeFormDto dtoB = new EmployeeFormDto();
            dtoB.setFullName("Hierarchy User B");
            dtoB.setDepartmentId(dept.getId());
            dtoB.setJobRoleId(role.getId());
            dtoB.setReportsToId(empA.getId()); // B -> A
            dtoB.setDateOfJoining(LocalDate.of(2026, 1, 10));
            dtoB.setStatus(EmployeeStatus.ACTIVE);
            empB = employeeService.createEmployee(dtoB);

            // Self-reporting A -> A must fail
            final UUID empAId = empA.getId();
            final UUID empBId = empB.getId();

            EmployeeFormDto selfDto = new EmployeeFormDto();
            selfDto.setFullName("Hierarchy User A");
            selfDto.setDepartmentId(dept.getId());
            selfDto.setJobRoleId(role.getId());
            selfDto.setReportsToId(empAId);
            selfDto.setDateOfJoining(LocalDate.of(2026, 1, 10));
            selfDto.setStatus(EmployeeStatus.ACTIVE);

            BusinessException selfEx = assertThrows(BusinessException.class,
                    () -> employeeService.updateEmployee(empAId, selfDto));
            assertTrue(selfEx.getMessage().contains("cannot report directly to themselves"));

            // Cycle A -> B (while B -> A) must fail
            EmployeeFormDto cycleDto = new EmployeeFormDto();
            cycleDto.setFullName("Hierarchy User A");
            cycleDto.setDepartmentId(dept.getId());
            cycleDto.setJobRoleId(role.getId());
            cycleDto.setReportsToId(empBId);
            cycleDto.setDateOfJoining(LocalDate.of(2026, 1, 10));
            cycleDto.setStatus(EmployeeStatus.ACTIVE);

            BusinessException cycleEx = assertThrows(BusinessException.class,
                    () -> employeeService.updateEmployee(empAId, cycleDto));
            assertTrue(cycleEx.getMessage().contains("Circular reporting hierarchy"));
        } finally {
            if (empB != null) {
                employeeRepository.findById(empB.getId()).ifPresent(e -> {
                    e.setReportsTo(null);
                    employeeRepository.saveAndFlush(e);
                });
                employeeRepository.deleteById(empB.getId());
            }
            if (empA != null) {
                employeeRepository.deleteById(empA.getId());
            }
            jobRoleRepository.deleteById(role.getId());
            departmentRepository.deleteById(dept.getId());
        }
    }

    @Test
    @DisplayName("GAP-02: DepartmentService rejects circular parent assignments")
    @WithUserDetails("hr1@company.com")
    void departmentServiceRejectsCircularParentHierarchy() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        Department root = departmentService.createDepartment("RootDept-" + suffix, null, null);
        Department child = departmentService.createDepartment("ChildDept-" + suffix, root.getId(), null);

        try {
            BusinessException selfEx = assertThrows(BusinessException.class,
                    () -> departmentService.updateDepartment(root.getId(), root.getName(), root.getId(), null));
            assertTrue(selfEx.getMessage().contains("Circular department hierarchy"));

            BusinessException cycleEx = assertThrows(BusinessException.class,
                    () -> departmentService.updateDepartment(root.getId(), root.getName(), child.getId(), null));
            assertTrue(cycleEx.getMessage().contains("Circular department hierarchy"));
        } finally {
            departmentRepository.findById(child.getId()).ifPresent(c -> {
                c.setParentDepartment(null);
                departmentRepository.saveAndFlush(c);
            });
            departmentRepository.deleteById(child.getId());
            departmentRepository.deleteById(root.getId());
        }
    }

    @Test
    @DisplayName("GAP-02: /employees/new form renders data-dept-id attributes and role filter script")
    @WithUserDetails("hr1@company.com")
    void employeeFormRendersDataDeptIdAttributes() throws Exception {
        mockMvc.perform(get("/employees/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-dept-id=")))
                .andExpect(content().string(containsString("filterJobRoles")));
    }
}
