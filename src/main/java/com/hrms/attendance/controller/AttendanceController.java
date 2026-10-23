package com.hrms.attendance.controller;

import com.hrms.attendance.dto.AttendanceCorrectionDto;
import com.hrms.attendance.dto.DailyAttendanceSummaryDto;
import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.attendance.entity.WorkMode;
import com.hrms.attendance.service.AttendanceService;
import com.hrms.department.repository.DepartmentRepository;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.exception.BusinessException;
import com.hrms.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Controller managing employee self-service attendance punches and
 * HR administrator oversight and manual corrections.
 */
@Controller
@RequestMapping("/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;

    public AttendanceController(AttendanceService attendanceService,
                                EmployeeRepository employeeRepository,
                                DepartmentRepository departmentRepository) {
        this.attendanceService = attendanceService;
        this.employeeRepository = employeeRepository;
        this.departmentRepository = departmentRepository;
    }

    /**
     * Employee Self-Service: My Attendance page.
     */
    @GetMapping("/my")
    public String myAttendance(@AuthenticationPrincipal CustomUserDetails userDetails,
                               @RequestParam(required = false) Integer year,
                               @RequestParam(required = false) Integer month,
                               Model model) {
        UUID userId = userDetails.getUserId();
        Optional<Employee> empOpt = employeeRepository.findByUserId(userId);

        LocalDate now = LocalDate.now();
        int targetYear = (year != null && year >= 2020) ? year : now.getYear();
        int targetMonth = (month != null && month >= 1 && month <= 12) ? month : now.getMonthValue();

        Optional<AttendanceRecord> todayRecord = attendanceService.getTodayRecord(userId);

        model.addAttribute("employee", empOpt.orElse(null));
        model.addAttribute("todayRecord", todayRecord.orElse(null));
        model.addAttribute("workModes", WorkMode.values());
        model.addAttribute("currentDate", now);
        model.addAttribute("targetYear", targetYear);
        model.addAttribute("targetMonth", targetMonth);

        if (empOpt.isPresent()) {
            List<AttendanceRecord> monthlyRecords = attendanceService.getEmployeeMonthlyAttendance(
                    empOpt.get().getId(), targetYear, targetMonth);
            model.addAttribute("monthlyRecords", monthlyRecords);
        } else {
            model.addAttribute("monthlyRecords", List.of());
        }

        return "attendance/my";
    }

    /**
     * Clock-In endpoint.
     */
    @PostMapping("/clock-in")
    public String clockIn(@AuthenticationPrincipal CustomUserDetails userDetails,
                          @RequestParam(defaultValue = "ON_SITE") WorkMode workMode,
                          @RequestParam(required = false) String notes,
                          HttpServletRequest request,
                          RedirectAttributes redirectAttributes) {
        try {
            String clientIp = extractClientIp(request);
            attendanceService.clockIn(userDetails.getUserId(), workMode, clientIp, notes);
            redirectAttributes.addFlashAttribute("successMessage", "Clocked in successfully! Have a productive day.");
        } catch (BusinessException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/attendance/my";
    }

    /**
     * Clock-Out endpoint.
     */
    @PostMapping("/clock-out")
    public String clockOut(@AuthenticationPrincipal CustomUserDetails userDetails,
                           @RequestParam(required = false) String notes,
                           HttpServletRequest request,
                           RedirectAttributes redirectAttributes) {
        try {
            String clientIp = extractClientIp(request);
            attendanceService.clockOut(userDetails.getUserId(), clientIp, notes);
            redirectAttributes.addFlashAttribute("successMessage", "Clocked out successfully! Total work duration recorded.");
        } catch (BusinessException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/attendance/my";
    }

    /**
     * HR & Super Admin daily company attendance roster.
     */
    @GetMapping("/manage")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public String manageAttendance(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                   @RequestParam(required = false) UUID departmentId,
                                   Model model) {
        LocalDate selectedDate = (date != null) ? date : LocalDate.now();

        List<AttendanceRecord> records = attendanceService.getDailyAttendance(selectedDate, departmentId);
        DailyAttendanceSummaryDto summary = attendanceService.getDailySummary(selectedDate);

        model.addAttribute("selectedDate", selectedDate);
        model.addAttribute("selectedDepartmentId", departmentId);
        model.addAttribute("records", records);
        model.addAttribute("summary", summary);
        model.addAttribute("departments", departmentRepository.findAllWithRelationsOrderByName());
        model.addAttribute("statuses", AttendanceStatus.values());
        model.addAttribute("workModes", WorkMode.values());
        model.addAttribute("correctionDto", new AttendanceCorrectionDto());

        return "attendance/manage";
    }

    /**
     * HR Admin manual correction of an attendance record.
     */
    @PostMapping("/correct")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String correctAttendance(@Valid @ModelAttribute("correctionDto") AttendanceCorrectionDto dto,
                                    BindingResult bindingResult,
                                    @AuthenticationPrincipal CustomUserDetails userDetails,
                                    RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    bindingResult.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/attendance/manage?date=" + dto.getWorkDate();
        }

        try {
            attendanceService.correctAttendance(dto, userDetails.getUserId());
            redirectAttributes.addFlashAttribute("successMessage",
                    "Attendance record corrected successfully and logged to the audit trail.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Correction failed: " + ex.getMessage());
        }

        return "redirect:/attendance/manage?date=" + dto.getWorkDate();
    }

    private String extractClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }
}
