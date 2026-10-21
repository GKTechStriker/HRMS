package com.hrms.security;

import com.hrms.audit.AuditService;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.security.entity.Role;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collections;
import java.util.UUID;

/**
 * Controller for authentication pages, application shell dashboard,
 * session management (/sessions), and audit trail (/audit) (Section 6.1 & 8.2).
 */
@Controller
public class AuthAndShellController {

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final SessionManagementService sessionManagementService;
    private final AuditService auditService;
    private final com.hrms.attendance.service.AttendanceService attendanceService;
    private final boolean demoHintsEnabled;

    public AuthAndShellController(EmployeeRepository employeeRepository,
                                  DepartmentRepository departmentRepository,
                                  SessionManagementService sessionManagementService,
                                  AuditService auditService,
                                  com.hrms.attendance.service.AttendanceService attendanceService,
                                  @org.springframework.beans.factory.annotation.Value("${hrms.demo-hints.enabled:true}") boolean demoHintsEnabled) {
        this.employeeRepository = employeeRepository;
        this.departmentRepository = departmentRepository;
        this.sessionManagementService = sessionManagementService;
        this.auditService = auditService;
        this.attendanceService = attendanceService;
        this.demoHintsEnabled = demoHintsEnabled;
    }

    @GetMapping("/")
    public String rootRedirect() {
        return "redirect:/dashboard";
    }

    @GetMapping("/login")
    public String loginPage(Model model) {
        model.addAttribute("demoHintsEnabled", demoHintsEnabled);
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal CustomUserDetails principal,
                            HttpSession httpSession,
                            Model model) {
        model.addAttribute("principal", principal);
        model.addAttribute("currentSessionId", httpSession.getId());
        model.addAttribute("totalEmployees", employeeRepository.count());
        model.addAttribute("activeEmployees", employeeRepository.countByStatus(EmployeeStatus.ACTIVE));
        model.addAttribute("totalDepartments", departmentRepository.count());
        model.addAttribute("sessions", sessionManagementService.getSessionsForPrincipal(principal));

        attendanceService.getTodayRecord(principal.getUserId())
                .ifPresent(att -> model.addAttribute("todayAttendance", att));

        if (principal.getRole() == Role.SUPER_ADMIN || principal.getRole() == Role.HR_ADMIN) {
            model.addAttribute("recentAudits", auditService.getRecentAuditLogsForPrincipal(principal, 10));
        } else {
            model.addAttribute("recentAudits", Collections.emptyList());
            employeeRepository.findByUserId(principal.getUserId())
                    .ifPresent(emp -> model.addAttribute("myEmployeeProfile", emp));
        }

        return "dashboard";
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public String auditLogPage(@AuthenticationPrincipal CustomUserDetails principal,
                               Model model) {
        model.addAttribute("principal", principal);
        model.addAttribute("auditLogs", auditService.getRecentAuditLogsForPrincipal(principal, 50));
        return "audit";
    }

    @GetMapping("/sessions")
    public String sessionsPage(@AuthenticationPrincipal CustomUserDetails principal,
                               HttpSession httpSession,
                               Model model) {
        model.addAttribute("principal", principal);
        model.addAttribute("currentSessionId", httpSession.getId());
        model.addAttribute("sessions", sessionManagementService.getSessionsForPrincipal(principal));
        return "sessions";
    }

    @PostMapping("/sessions/{id}/revoke")
    public String revokeSession(@PathVariable("id") UUID sessionRecordId,
                                @AuthenticationPrincipal CustomUserDetails principal,
                                RedirectAttributes redirectAttributes) {
        try {
            sessionManagementService.revokeSession(sessionRecordId, principal);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Session revoked and recorded in the immutable audit trail.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/sessions";
    }

    @PostMapping("/audit/verify-dummy")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String verifyAuditGate(@RequestParam(name = "note", required = false) String note,
                                  RedirectAttributes redirectAttributes) {
        UUID verificationId = auditService.verifyDummyAuditWrite(note);
        redirectAttributes.addFlashAttribute("successMessage",
                "Week 2 Gate Verified: Audit entry recorded for targetId=" + verificationId);
        return "redirect:/dashboard";
    }
}
