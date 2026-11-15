package com.hrms.attendance.controller;

import com.hrms.attendance.entity.Holiday;
import com.hrms.attendance.service.HolidayService;
import com.hrms.exception.BusinessException;
import com.hrms.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Controller exposing public company holiday calendars and HR Admin holiday management (GAP-09).
 */
@Controller
@RequestMapping("/holidays")
public class HolidayController {

    private static final Logger log = LoggerFactory.getLogger(HolidayController.class);

    private final HolidayService holidayService;
    private final Clock clock;

    public HolidayController(HolidayService holidayService, Clock clock) {
        this.holidayService = holidayService;
        this.clock = clock;
    }

    /**
     * List company holidays chronologically (viewable by all authenticated employees).
     */
    @GetMapping
    public String listHolidays(Model model) {
        List<Holiday> holidays = holidayService.getAllHolidays();
        model.addAttribute("holidays", holidays);
        model.addAttribute("today", LocalDate.now(clock));
        return "holidays/list";
    }

    /**
     * HR Admin creates a new public or recurring holiday.
     */
    @PostMapping("/new")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String createHoliday(
            @RequestParam("name") String name,
            @RequestParam("holidayDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate holidayDate,
            @RequestParam(name = "recurring", defaultValue = "false") boolean recurring,
            @RequestParam(name = "description", required = false) String description,
            RedirectAttributes redirectAttributes) {
        try {
            Holiday holiday = holidayService.createHoliday(name, holidayDate, recurring, description);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Holiday '" + holiday.getName() + "' (" + holiday.getHolidayDate() + ") registered and audited.");
        } catch (BusinessException | ResourceNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (Exception ex) {
            log.error("Failed to register holiday", ex);
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to register holiday due to an unexpected error.");
        }
        return "redirect:/holidays";
    }

    /**
     * HR Admin deletes an existing holiday.
     */
    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public String deleteHoliday(@PathVariable("id") UUID id, RedirectAttributes redirectAttributes) {
        try {
            holidayService.deleteHoliday(id);
            redirectAttributes.addFlashAttribute("successMessage", "Holiday deleted and audited.");
        } catch (BusinessException | ResourceNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (Exception ex) {
            log.error("Failed to delete holiday ID={}", id, ex);
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to delete holiday due to an unexpected error.");
        }
        return "redirect:/holidays";
    }
}
