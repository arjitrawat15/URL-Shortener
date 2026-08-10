package com.shortener.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration class to enable Spring's scheduled task execution.
 * 
 * This enables the @Scheduled annotation used in background jobs
 * like ClickCounterSyncService.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
    // Configuration class - no additional code needed
}




