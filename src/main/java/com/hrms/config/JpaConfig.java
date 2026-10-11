package com.hrms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Core framework configuration enabling JPA Auditing (for created_at / updated_at),
 * AspectJ Auto-Proxying (for @Audited AOP aspect), and Scheduled background tasks.
 */
@Configuration
@EnableJpaAuditing
@EnableAspectJAutoProxy
@EnableScheduling
public class JpaConfig {
}
