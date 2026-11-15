package com.hrms.security;

import com.hrms.audit.AuditService;
import com.hrms.audit.entity.AuditLog;
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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
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
    private final com.hrms.leave.service.LeaveService leaveService;
    private final boolean demoHintsEnabled;

    public AuthAndShellController(EmployeeRepository employeeRepository,
                                  DepartmentRepository departmentRepository,
                                  SessionManagementService sessionManagementService,
                                  AuditService auditService,
                                  com.hrms.attendance.service.AttendanceService attendanceService,
                                  com.hrms.leave.service.LeaveService leaveService,
                                  @org.springframework.beans.factory.annotation.Value("${hrms.demo-hints.enabled:true}") boolean demoHintsEnabled) {
        this.employeeRepository = employeeRepository;
        this.departmentRepository = departmentRepository;
        this.sessionManagementService = sessionManagementService;
        this.auditService = auditService;
        this.attendanceService = attendanceService;
        this.leaveService = leaveService;
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
            model.addAttribute("pendingLeaveCount", leaveService.getPendingCount());
        } else {
            model.addAttribute("recentAudits", Collections.emptyList());
            employeeRepository.findByUserId(principal.getUserId())
                    .ifPresent(emp -> {
                        model.addAttribute("myEmployeeProfile", emp);
                        model.addAttribute("myRecentLeaves", leaveService.getEmployeeRequests(emp.getId()));
                    });
        }

        return "dashboard";
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public String auditLogPage(@AuthenticationPrincipal CustomUserDetails principal,
                               @RequestParam(name = "action", required = false) String action,
                               @RequestParam(name = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                               @RequestParam(name = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                               @RequestParam(name = "page", defaultValue = "0") int page,
                               @RequestParam(name = "size", defaultValue = "25") int size,
                               Model model) {
        int boundedPage = Math.max(page, 0);
        int boundedSize = Math.min(Math.max(size, 10), 100);
        Pageable pageable = PageRequest.of(boundedPage, boundedSize);
        Page<AuditLog> auditPage = auditService.searchAuditLogsPaged(principal, action, fromDate, toDate, pageable);

        long totalElements = auditPage.getTotalElements();
        long showingFrom = totalElements > 0 ? ((long) boundedPage * boundedSize + 1) : 0L;
        long showingTo = Math.min((long) (boundedPage + 1) * boundedSize, totalElements);

        model.addAttribute("principal", principal);
        model.addAttribute("auditPage", auditPage);
        model.addAttribute("auditLogs", auditPage.getContent());
        model.addAttribute("currentPage", boundedPage);
        model.addAttribute("pageSize", boundedSize);
        model.addAttribute("totalPages", auditPage.getTotalPages());
        model.addAttribute("totalElements", totalElements);
        model.addAttribute("showingFrom", showingFrom);
        model.addAttribute("showingTo", showingTo);
        model.addAttribute("selectedAction", action);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("availableActions", auditService.getDistinctActions());
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
        } catch (com.hrms.exception.BusinessException
                 | com.hrms.exception.ResourceNotFoundException
                 | org.springframework.security.access.AccessDeniedException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(AuthAndShellController.class)
                    .error("Unexpected error revoking sessionRecordId={}", sessionRecordId, ex);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Unable to revoke session due to an unexpected error.");
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
