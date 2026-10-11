package com.hrms.employee.service;

import com.hrms.audit.AuditContext;
import com.hrms.audit.Audited;
import com.hrms.department.entity.Department;
import com.hrms.department.entity.JobRole;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.department.repository.JobRoleRepository;
import com.hrms.employee.dto.CsvImportResultDto;
import com.hrms.employee.dto.EmployeeFormDto;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.exception.BusinessException;
import com.hrms.security.entity.Role;
import com.hrms.security.repository.UserRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * CSV Bulk Import Service with Dry-Run Validation Mode (Section 6.2 & Milestone 2 Gate).
 * Expected CSV Header:
 * full_name,email,phone,department,job_role,date_of_birth,date_of_joining,role
 */
@Service
public class EmployeeCsvImportService {

    private final EmployeeService employeeService;
    private final DepartmentRepository departmentRepository;
    private final JobRoleRepository jobRoleRepository;
    private final UserRepository userRepository;

    public EmployeeCsvImportService(EmployeeService employeeService,
                                    DepartmentRepository departmentRepository,
                                    JobRoleRepository jobRoleRepository,
                                    UserRepository userRepository) {
        this.employeeService = employeeService;
        this.departmentRepository = departmentRepository;
        this.jobRoleRepository = jobRoleRepository;
        this.userRepository = userRepository;
    }

    /**
     * Dry-run validation: parses and validates the entire CSV file without writing to the database.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('HR_ADMIN')")
    public CsvImportResultDto dryRunCsv(MultipartFile file) {
        return parseAndValidate(file, true);
    }

    /**
     * Live commit import: validates the entire file first; if 0 errors, imports all rows
     * in a single transaction and writes a summary audit log entry.
     */
    @Transactional
    @PreAuthorize("hasRole('HR_ADMIN')")
    @Audited(action = "EMPLOYEE_BULK_IMPORTED", targetType = "Employee")
    public CsvImportResultDto commitCsvImport(MultipartFile file) {
        CsvImportResultDto dryRunReport = parseAndValidate(file, false);
        if (dryRunReport.errorCount() > 0) {
            throw new BusinessException("CSV import aborted: " + dryRunReport.errorCount()
                    + " validation error(s) found. Run Dry-Run mode to inspect row errors.");
        }

        UUID batchId = UUID.randomUUID();
        List<String> allocatedCodes = new ArrayList<>();

        for (CsvImportResultDto.RowPreview row : dryRunReport.rows()) {
            Department dept = departmentRepository.findByNameIgnoreCase(row.departmentName())
                    .orElseGet(() -> {
                        Department d = new Department();
                        d.setName(row.departmentName());
                        return departmentRepository.save(d);
                    });

            final Department resolvedDept = dept;
            JobRole jobRole = jobRoleRepository.findByTitleIgnoreCaseAndDepartmentId(row.jobRoleTitle(), resolvedDept.getId())
                    .orElseGet(() -> {
                        JobRole jr = new JobRole();
                        jr.setTitle(row.jobRoleTitle());
                        jr.setLevel(2);
                        jr.setDepartment(resolvedDept);
                        return jobRoleRepository.save(jr);
                    });

            EmployeeFormDto dto = new EmployeeFormDto();
            dto.setFullName(row.fullName());
            dto.setEmail(row.email());
            dto.setPhone(row.phone());
            dto.setDepartmentId(resolvedDept.getId());
            dto.setJobRoleId(jobRole.getId());
            dto.setDateOfBirth(row.dateOfBirth());
            dto.setDateOfJoining(row.dateOfJoining());
            dto.setStatus(EmployeeStatus.ACTIVE);
            dto.setSystemRole("HR_ADMIN".equalsIgnoreCase(row.systemRole()) ? Role.HR_ADMIN : Role.EMPLOYEE);

            Employee created = employeeService.createEmployee(dto);
            allocatedCodes.add(created.getEmployeeCode());
        }

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("fileName", file.getOriginalFilename());
        auditPayload.put("importedCount", allocatedCodes.size());
        auditPayload.put("firstEmployeeCode", allocatedCodes.isEmpty() ? null : allocatedCodes.getFirst());
        auditPayload.put("lastEmployeeCode", allocatedCodes.isEmpty() ? null : allocatedCodes.getLast());

        AuditContext.setTarget(batchId, null, auditPayload);

        return new CsvImportResultDto(
                false,
                true,
                dryRunReport.totalRows(),
                dryRunReport.validRows(),
                0,
                dryRunReport.rows(),
                List.of()
        );
    }

    private CsvImportResultDto parseAndValidate(MultipartFile file, boolean dryRun) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Please select a non-empty CSV file to upload.");
        }

        List<CsvImportResultDto.RowPreview> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seenEmailsInFile = new HashSet<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                // Skip header line
                if (lineNum == 1 && trimmed.toLowerCase().contains("full_name")) {
                    continue;
                }

                String[] cols = trimmed.split(",", -1);
                List<String> rowErrors = new ArrayList<>();

                String fullName = cols.length > 0 ? cols[0].trim() : "";
                String email = cols.length > 1 ? cols[1].trim().toLowerCase() : "";
                String phone = cols.length > 2 ? cols[2].trim() : "";
                String departmentName = cols.length > 3 ? cols[3].trim() : "";
                String jobRoleTitle = cols.length > 4 ? cols[4].trim() : "";
                String dobStr = cols.length > 5 ? cols[5].trim() : "";
                String dojStr = cols.length > 6 ? cols[6].trim() : "";
                String roleStr = cols.length > 7 && !cols[7].trim().isEmpty() ? cols[7].trim().toUpperCase() : "EMPLOYEE";

                if (fullName.isEmpty()) {
                    rowErrors.add("Missing full_name");
                }
                if (email.isEmpty() || !email.contains("@")) {
                    rowErrors.add("Invalid or missing email");
                } else {
                    if (!seenEmailsInFile.add(email)) {
                        rowErrors.add("Duplicate email within CSV: " + email);
                    }
                    if (userRepository.existsByEmailIgnoreCase(email)) {
                        rowErrors.add("Email already exists in database: " + email);
                    }
                }
                if (departmentName.isEmpty()) {
                    rowErrors.add("Missing department");
                }
                if (jobRoleTitle.isEmpty()) {
                    rowErrors.add("Missing job_role");
                }

                LocalDate dob = null;
                if (!dobStr.isEmpty()) {
                    try {
                        dob = LocalDate.parse(dobStr);
                    } catch (Exception e) {
                        rowErrors.add("Invalid date_of_birth (expected YYYY-MM-DD): " + dobStr);
                    }
                }

                LocalDate doj = null;
                if (dojStr.isEmpty()) {
                    rowErrors.add("Missing date_of_joining");
                } else {
                    try {
                        doj = LocalDate.parse(dojStr);
                    } catch (Exception e) {
                        rowErrors.add("Invalid date_of_joining (expected YYYY-MM-DD): " + dojStr);
                    }
                }

                if (!roleStr.equals("EMPLOYEE") && !roleStr.equals("HR_ADMIN")) {
                    rowErrors.add("Invalid role (must be EMPLOYEE or HR_ADMIN): " + roleStr);
                }

                boolean isValid = rowErrors.isEmpty();
                String validationMsg = isValid ? "Ready to import" : String.join("; ", rowErrors);
                if (!isValid) {
                    errors.add("Line " + lineNum + " (" + (fullName.isEmpty() ? "Unknown" : fullName) + "): " + validationMsg);
                }

                rows.add(new CsvImportResultDto.RowPreview(
                        lineNum,
                        fullName,
                        email,
                        phone,
                        departmentName,
                        jobRoleTitle,
                        dob,
                        doj,
                        roleStr,
                        isValid,
                        validationMsg
                ));
            }
        } catch (Exception ex) {
            throw new BusinessException("Failed to read CSV file: " + ex.getMessage(), ex);
        }

        int validCount = (int) rows.stream().filter(CsvImportResultDto.RowPreview::valid).count();
        return new CsvImportResultDto(
                dryRun,
                false,
                rows.size(),
                validCount,
                errors.size(),
                rows,
                errors
        );
    }
}
