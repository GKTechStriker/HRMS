package com.hrms.attendance.repository;

import com.hrms.attendance.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    Optional<Holiday> findByHolidayDate(LocalDate holidayDate);

    boolean existsByHolidayDate(LocalDate holidayDate);

    List<Holiday> findByHolidayDateBetweenOrderByHolidayDateAsc(LocalDate startDate, LocalDate endDate);

    List<Holiday> findByRecurringTrue();

    default boolean isPublicHoliday(LocalDate date) {
        if (existsByHolidayDate(date)) {
            return true;
        }
        MonthDay target = MonthDay.from(date);
        return findByRecurringTrue().stream()
                .anyMatch(h -> MonthDay.from(h.getHolidayDate()).equals(target));
    }
}
