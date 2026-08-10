package com.shortener.service;

import com.shortener.entity.UrlAnalytics;

/**
 * Interface for publishing analytics events asynchronously.
 * 
 * This interface abstracts the analytics event publishing mechanism,
 * allowing for different implementations (Kafka, RabbitMQ, etc.).
 * 
 * The publisher should be non-blocking and fire-and-forget to ensure
 * redirect performance is not impacted by analytics processing.
 */
public interface AnalyticsEventPublisher {

    /**
     * Publishes an analytics event asynchronously.
     * 
     * This method should:
     * - Be non-blocking (fire-and-forget)
     * - Handle failures gracefully (log but don't throw)
     * - Queue events for batch processing
     * 
     * @param analytics the analytics event to publish
     */
    void publishClickEvent(UrlAnalytics analytics);
}




