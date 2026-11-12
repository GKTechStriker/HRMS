package com.hrms.config;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Thymeleaf formatting helper bound as @timeFmt in SpEL templates.
 * Safely converts Instant and OffsetDateTime values to the configured business ZoneId
 * and formats nullable attendance durations without SpEL arithmetic errors.
 */
@Component("timeFmt")
public class TimeFormatHelper {

    private final ZoneId businessZoneId;

    public TimeFormatHelper(ZoneId businessZoneId) {
        this.businessZoneId = businessZoneId;
    }

    public String formatInstant(Instant instant, String pattern) {
        if (instant == null) {
            return "-";
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                Objects.requireNonNullElse(pattern, "dd MMM yyyy, hh:mm a"), Locale.ENGLISH);
        return instant.atZone(businessZoneId).format(formatter);
    }

    public String formatDateTime(OffsetDateTime dateTime, String pattern) {
        if (dateTime == null) {
            return "-";
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                Objects.requireNonNullElse(pattern, "hh:mm a"), Locale.ENGLISH);
        return dateTime.atZoneSameInstant(businessZoneId).format(formatter);
    }

    public String formatDateTimeOrEmpty(OffsetDateTime dateTime, String pattern) {
        if (dateTime == null) {
            return "";
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                Objects.requireNonNullElse(pattern, "HH:mm"), Locale.ENGLISH);
        return dateTime.atZoneSameInstant(businessZoneId).format(formatter);
    }

    public String formatDuration(Integer durationMinutes) {
        if (durationMinutes == null) {
            return "-";
        }
        return (durationMinutes / 60) + "h " + (durationMinutes % 60) + "m";
    }
}
