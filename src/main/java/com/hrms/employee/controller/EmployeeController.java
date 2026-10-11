package com.hrms.employee.controller;

import com.hrms.department.service.DepartmentService;
import com.hrms.employee.dto.CsvImportResultDto;
import com.hrms.employee.dto.EmployeeFormDto;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.employee.service.EmployeeCsvImportService;
import com.hrms.employee.service.EmployeeService;
import com.hrms.security.CustomUserDetails;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * Controller for Employee Lifecycle Management & CSV Bulk Import (Section 6.2 & 8.2).
 */
@Controller
@RequestMapping("/employees")
public class EmployeeController {

    private final EmployeeService employeeService;
    private final EmployeeCsvImportService csvImportService;
    private final DepartmentService departmentService;
    private final EmployeeRepository employeeRepository;

    public EmployeeController(EmployeeService employeeService,
                              EmployeeCsvImportService csvImportService,
                              DepartmentService departmentService,
                              EmployeeRepository employeeRepository) {
        this.employeeService = employeeService;
        this.csvImportService = csvImportService;
        this.departmentService = departmentService;
        this.employeeRepository = employeeRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public String employeeDirectory(@RequestParam(name = "search", required = false) String search,
                                    @RequestParam(name = "departmentId", required = false) UUID departmentId,
                                    @RequestParam(name = "jobRoleId", required = false) UUID jobRoleId,
                                    @RequestParam(name = "status", required = false) EmployeeStatus status,
                                    @RequestParam(name = "page", defaultValue = "0") int page,
                                    @RequestParam(name = "size", defaultValue = "15") int size,
                                    Model model) {
        Page<Employee> employeePage = employeeService.searchDirectory(
                search,
                departmentId,
                jobRoleId,
                status,
                PageRequest.of(Math.max(0, page), Math.min(100, Math.max(5, size)), Sort.by("employeeCode").ascending())
        );

        model.addAttribute("employeePage", employeePage);
        model.addAttribute("departments", departmentService.getAllDepartments());
        model.addAttribute("jobRoles", departmentService.getAllJobRoles());
        model.addAttribute("statuses", EmployeeStatus.values());
        model.addAttribute("search", search);
        model.addAttribute("selectedDepartmentId", departmentId);
        model.addAttribute("selectedJobRoleId", jobRoleId);
        model.addAttribute("selectedStatus", status);
        return "employees/list";
    }

    @GetMapping("/new")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String newEmployeeForm(Model model) {
        model.addAttribute("employeeForm", new EmployeeFormDto());
        model.addAttribute("editMode", false);
        populateFormLookups(model);
        return "employees/form";
    }

    @PostMapping("/new")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String createEmployee(@Valid @ModelAttribute("employeeForm") EmployeeFormDto formDto,
                                 BindingResult bindingResult,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("editMode", false);
            populateFormLookups(model);
            return "employees/form";
        }
        try {
            Employee created = employeeService.createEmployee(formDto);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Employee " + created.getFullName() + " (" + created.getEmployeeCode() + ") created and audited.");
            return "redirect:/employees/" + created.getId();
        } catch (Exception ex) {
            model.addAttribute("editMode", false);
            model.addAttribute("errorMessage", ex.getMessage());
            populateFormLookups(model);
            return "employees/form";
        }
    }

    @GetMapping("/me")
    public String myProfile(@AuthenticationPrincipal CustomUserDetails principal) {
        return employeeRepository.findByUserId(principal.getUserId())
                .map(emp -> "redirect:/employees/" + emp.getId())
                .orElse("redirect:/dashboard");
    }

    @GetMapping("/{id}")
    public String employeeProfile(@PathVariable("id") UUID id,
                                  @AuthenticationPrincipal CustomUserDetails principal,
                                  Model model) {
        Employee employee = employeeService.getEmployeeForPrincipal(id, principal);
        model.addAttribute("employee", employee);
        return "employees/profile";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String editEmployeeForm(@PathVariable("id") UUID id,
                                   @AuthenticationPrincipal CustomUserDetails principal,
                                   Model model) {
        Employee emp = employeeService.getEmployeeForPrincipal(id, principal);
        EmployeeFormDto form = new EmployeeFormDto();
        form.setFullName(emp.getFullName());
        form.setEmail(emp.getUser() != null ? emp.getUser().getEmail() : null);
        form.setDepartmentId(emp.getDepartment().getId());
        form.setJobRoleId(emp.getJobRole().getId());
        form.setReportsToId(emp.getReportsTo() != null ? emp.getReportsTo().getId() : null);
        form.setPhone(emp.getPhone());
        form.setDateOfBirth(emp.getDateOfBirth());
        form.setDateOfJoining(emp.getDateOfJoining());
        form.setStatus(emp.getStatus());

        model.addAttribute("employee", emp);
        model.addAttribute("employeeForm", form);
        model.addAttribute("editMode", true);
        populateFormLookups(model);
        return "employees/form";
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String updateEmployee(@PathVariable("id") UUID id,
                                 @Valid @ModelAttribute("employeeForm") EmployeeFormDto formDto,
                                 BindingResult bindingResult,
                                 @AuthenticationPrincipal CustomUserDetails principal,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("employee", employeeService.getEmployeeForPrincipal(id, principal));
            model.addAttribute("editMode", true);
            populateFormLookups(model);
            return "employees/form";
        }
        try {
            Employee updated = employeeService.updateEmployee(id, formDto);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Employee " + updated.getEmployeeCode() + " updated; field-level changes recorded in audit log.");
            return "redirect:/employees/" + updated.getId();
        } catch (Exception ex) {
            model.addAttribute("employee", employeeService.getEmployeeForPrincipal(id, principal));
            model.addAttribute("editMode", true);
            model.addAttribute("errorMessage", ex.getMessage());
            populateFormLookups(model);
            return "employees/form";
        }
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String deactivateEmployee(@PathVariable("id") UUID id,
                                     @RequestParam(name = "suspendLogin", defaultValue = "true") boolean suspendLogin,
                                     @RequestParam(name = "reason", required = false) String reason,
                                     RedirectAttributes redirectAttributes) {
        try {
            Employee deactivated = employeeService.deactivateEmployee(id, suspendLogin, reason);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Employee " + deactivated.getEmployeeCode() + " soft-deactivated (historical records preserved).");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/employees/" + id;
    }

    @GetMapping("/import")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String csvImportPage() {
        return "employees/import";
    }

    @PostMapping("/import")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String handleCsvImport(@RequestParam("file") MultipartFile file,
                                  @RequestParam(name = "mode", defaultValue = "dryRun") String mode,
                                  Model model,
                                  RedirectAttributes redirectAttributes) {
        try {
            boolean isDryRun = !"commit".equalsIgnoreCase(mode);
            if (isDryRun) {
                CsvImportResultDto report = csvImportService.dryRunCsv(file);
                model.addAttribute("report", report);
                model.addAttribute("uploadedFileName", file.getOriginalFilename());
                return "employees/import";
            } else {
                CsvImportResultDto report = csvImportService.commitCsvImport(file);
                redirectAttributes.addFlashAttribute("successMessage",
                        "Successfully imported " + report.validRows() + " employee record(s) from CSV and recorded audit entry.");
                return "redirect:/employees";
            }
        } catch (Exception ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            return "employees/import";
        }
    }

    private void populateFormLookups(Model model) {
        model.addAttribute("departments", departmentService.getAllDepartments());
        model.addAttribute("jobRoles", departmentService.getAllJobRoles());
        model.addAttribute("activeEmployees", employeeRepository.findByStatus(EmployeeStatus.ACTIVE));
        model.addAttribute("statuses", EmployeeStatus.values());
    }
}
