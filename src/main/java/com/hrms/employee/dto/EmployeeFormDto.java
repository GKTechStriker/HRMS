package com.hrms.employee.dto;

import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.security.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Form-backing DTO for creating and editing Employee records (Section 6.2).
 */
public class EmployeeFormDto {

    @NotBlank(message = "Full name is required")
    private String fullName;

    @Email(message = "Please enter a valid work email address")
    private String email;

    private String password;

    private Role systemRole = Role.EMPLOYEE;

    @NotNull(message = "Department is required")
    private UUID departmentId;

    @NotNull(message = "Job role is required")
    private UUID jobRoleId;

    private UUID reportsToId;

    private String phone;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateOfBirth;

    @NotNull(message = "Date of joining is required")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateOfJoining = LocalDate.now();

    private EmployeeStatus status = EmployeeStatus.ACTIVE;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Role getSystemRole() {
        return systemRole;
    }

    public void setSystemRole(Role systemRole) {
        this.systemRole = systemRole;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(UUID departmentId) {
        this.departmentId = departmentId;
    }

    public UUID getJobRoleId() {
        return jobRoleId;
    }

    public void setJobRoleId(UUID jobRoleId) {
        this.jobRoleId = jobRoleId;
    }

    public UUID getReportsToId() {
        return reportsToId;
    }

    public void setReportsToId(UUID reportsToId) {
        this.reportsToId = reportsToId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public LocalDate getDateOfJoining() {
        return dateOfJoining;
    }

    public void setDateOfJoining(LocalDate dateOfJoining) {
        this.dateOfJoining = dateOfJoining;
    }

    public EmployeeStatus getStatus() {
        return status;
    }

    public void setStatus(EmployeeStatus status) {
        this.status = status;
    }
}
