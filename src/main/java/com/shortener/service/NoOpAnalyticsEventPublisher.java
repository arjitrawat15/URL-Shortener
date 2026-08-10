package com.shortener.service;

import com.shortener.entity.UrlAnalytics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * No-op implementation of AnalyticsEventPublisher.
 * 
 * This is a placeholder implementation that logs events but doesn't
 * actually publish them. In production, replace this with a real
 * implementation (Kafka, RabbitMQ, etc.).
 * 
 * The @ConditionalOnMissingBean ensures this is only used if no
 * other implementation is provided.
 */
@Component
@ConditionalOnMissingBean(name = "analyticsEventPublisher")
public class NoOpAnalyticsEventPublisher implements AnalyticsEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(NoOpAnalyticsEventPublisher.class);

    @Override
    public void publishClickEvent(UrlAnalytics analytics) {
        // Log the event (in production, this would publish to message queue)
        logger.debug("Analytics event (no-op): shortCode={}, timestamp={}, ip={}",
                analytics.getShortCode(),
                analytics.getClickTimestamp(),
                analytics.getIpAddress());
        
        // TODO: Replace with actual message queue publisher
        // Example:
        // kafkaTemplate.send("url-clicks", analytics);
    }
}




