package com.hrms.department.controller;

import com.hrms.department.service.DepartmentService;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * Controller for Department & Organizational Structure Management (Section 6.5 & 8.2).
 */
@Controller
@RequestMapping("/departments")
public class DepartmentController {

    private final DepartmentService departmentService;
    private final EmployeeRepository employeeRepository;

    public DepartmentController(DepartmentService departmentService,
                                EmployeeRepository employeeRepository) {
        this.departmentService = departmentService;
        this.employeeRepository = employeeRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public String listDepartments(Model model) {
        model.addAttribute("departments", departmentService.getAllDepartments());
        model.addAttribute("headcounts", departmentService.getDepartmentHeadcounts());
        model.addAttribute("jobRoles", departmentService.getAllJobRoles());
        return "departments/list";
    }

    @GetMapping("/new")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String newDepartmentForm(Model model) {
        model.addAttribute("departments", departmentService.getAllDepartments());
        model.addAttribute("activeEmployees", employeeRepository.findByStatus(EmployeeStatus.ACTIVE));
        return "departments/form";
    }

    @PostMapping("/new")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String createDepartment(@RequestParam("name") String name,
                                   @RequestParam(value = "parentDepartmentId", required = false) UUID parentDepartmentId,
                                   @RequestParam(value = "headEmployeeId", required = false) UUID headEmployeeId,
                                   RedirectAttributes redirectAttributes) {
        try {
            departmentService.createDepartment(name, parentDepartmentId, headEmployeeId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Department '" + name.trim() + "' created and logged to audit trail.");
            return "redirect:/departments";
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/departments/new";
        }
    }

    @PostMapping("/job-roles")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String createJobRole(@RequestParam("title") String title,
                                @RequestParam(value = "level", defaultValue = "1") int level,
                                @RequestParam("departmentId") UUID departmentId,
                                RedirectAttributes redirectAttributes) {
        try {
            departmentService.createJobRole(title, level, departmentId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Job Role '" + title.trim() + "' created and logged to audit trail.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/departments";
    }
}
