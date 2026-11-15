package com.hrms.attendance.service;

import com.hrms.attendance.entity.Holiday;
import com.hrms.attendance.repository.HolidayRepository;
import com.hrms.audit.AuditContext;
import com.hrms.audit.Audited;
import com.hrms.exception.BusinessException;
import com.hrms.exception.ResourceNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service managing company calendar holidays (GAP-09).
 */
@Service
public class HolidayService {

    private final HolidayRepository holidayRepository;

    public HolidayService(HolidayRepository holidayRepository) {
        this.holidayRepository = holidayRepository;
    }

    @Transactional(readOnly = true)
    public List<Holiday> getAllHolidays() {
        return holidayRepository.findAllByOrderByHolidayDateAsc();
    }

    @Transactional
    @PreAuthorize("hasRole('HR_ADMIN')")
    @Audited(action = "HOLIDAY_CREATED", targetType = "Holiday")
    public Holiday createHoliday(String name, LocalDate holidayDate, boolean recurring, String description) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("Holiday name is required.");
        }
        if (holidayDate == null) {
            throw new BusinessException("Holiday date is required.");
        }
        if (holidayRepository.existsByHolidayDate(holidayDate)) {
            throw new BusinessException("A holiday is already registered for date: " + holidayDate);
        }

        Holiday holiday = new Holiday(name.trim(), holidayDate, recurring,
                description != null ? description.trim() : null);
        holiday = holidayRepository.save(holiday);

        AuditContext.setTarget(holiday.getId(), null, toAuditSnapshot(holiday));
        return holiday;
    }

    @Transactional
    @PreAuthorize("hasRole('HR_ADMIN')")
    @Audited(action = "HOLIDAY_DELETED", targetType = "Holiday")
    public void deleteHoliday(UUID id) {
        Holiday holiday = holidayRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.forEntity("Holiday", id.toString()));

        Map<String, Object> beforeSnapshot = toAuditSnapshot(holiday);
        holidayRepository.delete(holiday);
        AuditContext.setTarget(id, beforeSnapshot, null);
    }

    private Map<String, Object> toAuditSnapshot(Holiday holiday) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", holiday.getName());
        map.put("holidayDate", holiday.getHolidayDate() != null ? holiday.getHolidayDate().toString() : null);
        map.put("recurring", holiday.isRecurring());
        map.put("description", holiday.getDescription());
        return map;
    }
}
