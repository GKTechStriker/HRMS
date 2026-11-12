package com.hrms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Centralizes business timezone and Clock beans so all date/time operations
 * resolve deterministically rather than drifting with the host JVM default timezone.
 */
@Configuration
public class ClockConfig {

    @Bean
    public ZoneId businessZoneId(@Value("${hrms.timezone:Asia/Kolkata}") String timezone) {
        return ZoneId.of(timezone);
    }

    @Bean
    public Clock businessClock(ZoneId businessZoneId) {
        return Clock.system(businessZoneId);
    }
}
