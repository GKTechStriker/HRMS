package com.hrms.leave.controller;

import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.exception.BusinessException;
import com.hrms.leave.dto.LeaveActionDto;
import com.hrms.leave.dto.LeaveApplicationDto;
import com.hrms.leave.dto.LeaveBalanceSummaryDto;
import com.hrms.leave.entity.LeaveRequest;
import com.hrms.leave.repository.LeaveTypeRepository;
import com.hrms.leave.service.LeaveBalanceService;
import com.hrms.leave.service.LeaveService;
import com.hrms.security.CustomUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Controller handling employee leave self-service and HR leave approvals.
 */
@Controller
@RequestMapping("/leaves")
public class LeaveController {

    private final LeaveService leaveService;
    private final LeaveBalanceService leaveBalanceService;
    private final LeaveTypeRepository leaveTypeRepository;
    private final EmployeeRepository employeeRepository;
    private final Clock clock;

    public LeaveController(LeaveService leaveService,
                           LeaveBalanceService leaveBalanceService,
                           LeaveTypeRepository leaveTypeRepository,
                           EmployeeRepository employeeRepository,
                           Clock clock) {
        this.leaveService = leaveService;
        this.leaveBalanceService = leaveBalanceService;
        this.leaveTypeRepository = leaveTypeRepository;
        this.employeeRepository = employeeRepository;
        this.clock = clock;
    }

    /**
     * Employee Self-Service: My Leaves & Balances.
     */
    @GetMapping("/my")
    public String myLeaves(@AuthenticationPrincipal CustomUserDetails userDetails, Model model) {
        UUID userId = userDetails.getUserId();
        Optional<Employee> empOpt = employeeRepository.findByUserId(userId);

        int currentYear = LocalDate.now(clock).getYear();

        if (empOpt.isPresent()) {
            Employee emp = empOpt.get();
            List<LeaveBalanceSummaryDto> balances = leaveBalanceService.getEmployeeBalances(emp.getId(), currentYear);
            List<LeaveRequest> requests = leaveService.getEmployeeRequests(emp.getId());

            model.addAttribute("employee", emp);
            model.addAttribute("balances", balances);
            model.addAttribute("requests", requests);
        } else {
            model.addAttribute("employee", null);
            model.addAttribute("balances", List.of());
            model.addAttribute("requests", List.of());
        }

        model.addAttribute("leaveTypes", leaveTypeRepository.findAll());
        model.addAttribute("applicationDto", new LeaveApplicationDto());
        model.addAttribute("currentYear", currentYear);

        return "leaves/my";
    }

    /**
     * Submit a leave application.
     */
    @PostMapping("/apply")
    public String applyForLeave(@Valid @ModelAttribute("applicationDto") LeaveApplicationDto dto,
                                BindingResult bindingResult,
                                @AuthenticationPrincipal CustomUserDetails userDetails,
                                RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    bindingResult.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/leaves/my";
        }

        try {
            leaveService.applyForLeave(userDetails.getUserId(), dto);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Leave application submitted successfully! Provisional hold applied to your balance.");
        } catch (BusinessException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Error submitting leave: " + ex.getMessage());
        }

        return "redirect:/leaves/my";
    }

    /**
     * Cancel own pending leave request.
     */
    @PostMapping("/{id}/cancel")
    public String cancelLeave(@PathVariable("id") UUID requestId,
                              @AuthenticationPrincipal CustomUserDetails userDetails,
                              RedirectAttributes redirectAttributes) {
        try {
            leaveService.cancelLeave(requestId, userDetails.getUserId());
            redirectAttributes.addFlashAttribute("successMessage", "Leave request cancelled. Provisional hold released.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to cancel leave: " + ex.getMessage());
        }
        return "redirect:/leaves/my";
    }

    /**
     * HR & Super Admin: Leave Oversight & Approval Queue.
     */
    @GetMapping("/manage")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public String manageLeaves(Model model) {
        model.addAttribute("pendingRequests", leaveService.getPendingRequests());
        model.addAttribute("pendingCount", leaveService.getPendingCount());
        model.addAttribute("allRequests", leaveService.getAllRequests());
        model.addAttribute("actionDto", new LeaveActionDto());
        return "leaves/manage";
    }

    /**
     * HR Admin Approve Leave.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String approveLeave(@PathVariable("id") UUID requestId,
                               @AuthenticationPrincipal CustomUserDetails userDetails,
                               RedirectAttributes redirectAttributes) {
        try {
            leaveService.approveLeave(requestId, userDetails.getUserId());
            redirectAttributes.addFlashAttribute("successMessage",
                    "Leave request APPROVED. Attendance records synchronized to 'ON_LEAVE'.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Approval failed: " + ex.getMessage());
        }
        return "redirect:/leaves/manage";
    }

    /**
     * HR Admin Reject Leave.
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String rejectLeave(@PathVariable("id") UUID requestId,
                              @RequestParam(required = false, defaultValue = "Declined by HR") String rejectionReason,
                              @AuthenticationPrincipal CustomUserDetails userDetails,
                              RedirectAttributes redirectAttributes) {
        try {
            leaveService.rejectLeave(requestId, rejectionReason, userDetails.getUserId());
            redirectAttributes.addFlashAttribute("successMessage",
                    "Leave request REJECTED. Provisional hold released back to employee.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Rejection failed: " + ex.getMessage());
        }
        return "redirect:/leaves/manage";
    }
}
