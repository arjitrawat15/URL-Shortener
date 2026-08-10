package com.shortener.service;

import com.shortener.entity.UrlAnalytics;
import com.shortener.entity.UrlMapping;
import com.shortener.repository.UrlMappingRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Service for URL redirection operations (READ PATH).
 * 
 * This is the critical read path that must be extremely fast.
 * Every millisecond counts because this endpoint will be called
 * millions of times per day.
 * 
 * Performance Strategy:
 * 
 * 1. Cache-First Strategy:
 *    - Check Redis cache FIRST (99%+ of requests should hit cache)
 *    - Cache hit: Return immediately (< 1ms)
 *    - Cache miss: Query database, then cache result
 *    - Why cache-first: Redis is in-memory, DB is on disk (slower)
 * 
 * 2. Why redirect must be extremely fast:
 *    - User experience: Slow redirects feel broken
 *    - SEO impact: Search engines penalize slow redirects
 *    - Scale: At 1M requests/day, 10ms delay = 2.7 hours wasted
 *    - Cost: Faster = fewer servers needed
 * 
 * 3. Failure Handling:
 * 
 *    Redis Down:
 *    - Fall back to database (slower but still works)
 *    - Log warning but don't fail the request
 *    - Cache write failures are non-critical (can rebuild)
 *    - Click counter failures are logged but don't block redirect
 * 
 *    Database Down:
 *    - If cache has data: Still serve from cache
 *    - If cache miss: Return 503 Service Unavailable
 *    - Log critical error for monitoring
 *    - Health check should detect this
 * 
 * 4. Analytics (Non-Blocking):
 *    - Analytics must NEVER block the redirect
 *    - Use async event publisher (fire-and-forget)
 *    - If publisher fails, log but continue
 *    - Analytics are eventually consistent
 * 
 * 5. Click Counting:
 *    - Use Redis atomic increment (fast, thread-safe)
 *    - Do NOT update DB click_count here (too slow)
 *    - Background job syncs Redis → DB periodically
 *    - Redis is source of truth for real-time counts
 */
@Service
public class UrlRedirectService {

    private static final Logger logger = LoggerFactory.getLogger(UrlRedirectService.class);

    private final UrlMappingRepository urlMappingRepository;
    private final ShortCodeGenerator shortCodeGenerator;
    private final RedisTemplate<String, String> redisTemplate;
    private final AnalyticsEventPublisher analyticsEventPublisher;

    /**
     * Redis key prefix for URL mappings.
     * Format: "url:{shortCode}" -> longUrl
     */
    private static final String REDIS_URL_KEY_PREFIX = "url:";

    /**
     * Redis key prefix for click counters.
     * Format: "clicks:{shortCode}" -> count (atomic increment)
     */
    private static final String REDIS_CLICKS_KEY_PREFIX = "clicks:";

    /**
     * Cache TTL in days for active URLs.
     * Should match the TTL used in UrlShorteningService.
     */
    private static final int CACHE_TTL_DAYS = 7;

    public UrlRedirectService(
            UrlMappingRepository urlMappingRepository,
            ShortCodeGenerator shortCodeGenerator,
            RedisTemplate<String, String> redisTemplate,
            AnalyticsEventPublisher analyticsEventPublisher) {
        this.urlMappingRepository = urlMappingRepository;
        this.shortCodeGenerator = shortCodeGenerator;
        this.redisTemplate = redisTemplate;
        this.analyticsEventPublisher = analyticsEventPublisher;
    }

    /**
     * Resolves a short code to a long URL for redirection.
     * 
     * Flow:
     * 1. Validate short code format
     * 2. Check Redis cache (fast path - 99%+ of requests)
     * 3. If cache miss: Query database
     * 4. Validate URL is active and not expired
     * 5. Cache result in Redis
     * 6. Increment click counter (async, non-blocking)
     * 7. Publish analytics event (async, non-blocking)
     * 8. Return long URL
     * 
     * @param shortCode the short code to resolve
     * @param request HTTP request for analytics (IP, User-Agent, Referer)
     * @return the long URL to redirect to
     * @throws IllegalArgumentException if short code format is invalid
     * @throws UrlNotFoundException if URL doesn't exist
     * @throws UrlExpiredException if URL has expired
     * @throws UrlInactiveException if URL is inactive
     */
    public String resolveShortCode(String shortCode, HttpServletRequest request) {
        logger.debug("Resolving short code: {}", shortCode);

        // Step 1: Validate short code format
        if (!shortCodeGenerator.isValidShortCodeFormat(shortCode)) {
            throw new IllegalArgumentException("Invalid short code format: " + shortCode);
        }

        String longUrl;

        // Step 2: Check Redis cache FIRST (cache-first strategy)
        // This is the fast path - 99%+ of requests should hit here
        longUrl = getFromCache(shortCode);

        if (longUrl != null) {
            // Cache hit - fastest path (< 1ms)
            logger.debug("Cache hit for short code: {}", shortCode);
            
            // Increment click counter (async, non-blocking)
            incrementClickCounter(shortCode);
            
            // Publish analytics event (async, non-blocking)
            publishAnalytics(shortCode, request, true);
            
            return longUrl;
        }

        // Step 3: Cache miss - query database (slower path)
        logger.debug("Cache miss for short code: {}, querying database", shortCode);
        
        UrlMapping urlMapping = urlMappingRepository.findValidByShortCode(
                shortCode, 
                LocalDateTime.now()
        ).orElseThrow(() -> {
            // Check if URL exists but is expired/inactive
            UrlMapping existing = urlMappingRepository.findByShortCode(shortCode)
                    .orElse(null);
            
            if (existing == null) {
                logger.warn("Short code not found: {}", shortCode);
                return new UrlNotFoundException("Short code not found: " + shortCode);
            }
            
            if (existing.isExpired()) {
                logger.warn("Short code expired: {}", shortCode);
                return new UrlExpiredException("Short code has expired: " + shortCode);
            }
            
            if (!existing.getIsActive()) {
                logger.warn("Short code inactive: {}", shortCode);
                return new UrlInactiveException("Short code is inactive: " + shortCode);
            }
            
            // Fallback (shouldn't happen)
            return new UrlNotFoundException("Short code not found: " + shortCode);
        });

        longUrl = urlMapping.getLongUrl();

        // Step 4: Cache the result for future requests
        // This is non-critical - if it fails, we just query DB next time
        cacheUrlMapping(shortCode, longUrl);

        // Step 5: Increment click counter (async, non-blocking)
        incrementClickCounter(shortCode);

        // Step 6: Publish analytics event (async, non-blocking)
        publishAnalytics(shortCode, request, false);

        logger.debug("Resolved short code {} to {}", shortCode, longUrl);
        return longUrl;
    }

    /**
     * Gets the long URL from Redis cache.
     * 
     * Failure handling:
     * - If Redis is down, returns null (fallback to DB)
     * - Logs warning but doesn't fail the request
     * - Cache failures are non-critical
     * 
     * @param shortCode the short code
     * @return the long URL if found in cache, null otherwise
     */
    private String getFromCache(String shortCode) {
        try {
            String key = REDIS_URL_KEY_PREFIX + shortCode;
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            // Redis down or error - fallback to database
            // This is non-critical - we can still serve from DB
            logger.warn("Failed to read from Redis cache for short code {}: {}", 
                    shortCode, e.getMessage());
            return null;
        }
    }

    /**
     * Caches the URL mapping in Redis.
     * 
     * Failure handling:
     * - If Redis write fails, log warning but continue
     * - Cache is for performance, not correctness
     * - Can be rebuilt from database if needed
     * 
     * @param shortCode the short code
     * @param longUrl the long URL to cache
     */
    private void cacheUrlMapping(String shortCode, String longUrl) {
        try {
            String key = REDIS_URL_KEY_PREFIX + shortCode;
            redisTemplate.opsForValue().set(
                    key, 
                    longUrl, 
                    CACHE_TTL_DAYS, 
                    TimeUnit.DAYS
            );
            logger.debug("Cached URL mapping: {} -> {}", shortCode, longUrl);
        } catch (Exception e) {
            // Cache write failure is non-critical
            // Log warning but don't fail the request
            logger.warn("Failed to cache URL mapping in Redis for short code {}: {}", 
                    shortCode, e.getMessage());
        }
    }

    /**
     * Increments the click counter in Redis atomically.
     * 
     * This is fast and thread-safe using Redis atomic operations.
     * The counter is used for real-time click statistics.
     * 
     * Failure handling:
     * - If Redis is down, log warning but continue
     * - Click counting is non-critical for redirect functionality
     * - Counters can be synced from analytics table if needed
     * 
     * Note: We do NOT update DB click_count here because:
     * - DB writes are slow (disk I/O)
     * - Would block the redirect request
     * - Background job syncs Redis → DB periodically
     * 
     * @param shortCode the short code
     */
    private void incrementClickCounter(String shortCode) {
        try {
            String key = REDIS_CLICKS_KEY_PREFIX + shortCode;
            redisTemplate.opsForValue().increment(key);
            // Note: We don't set TTL on counters - they persist until cleanup
            logger.debug("Incremented click counter for short code: {}", shortCode);
        } catch (Exception e) {
            // Counter increment failure is non-critical
            // Log warning but don't fail the request
            logger.warn("Failed to increment click counter in Redis for short code {}: {}", 
                    shortCode, e.getMessage());
        }
    }

    /**
     * Publishes an analytics event asynchronously.
     * 
     * This is completely non-blocking and fire-and-forget.
     * Analytics failures should NEVER impact redirect performance.
     * 
     * Failure handling:
     * - If publisher fails, log warning but continue
     * - Analytics are eventually consistent
     * - Events can be retried or batched later
     * 
     * @param shortCode the short code
     * @param request HTTP request for extracting metadata
     * @param fromCache whether the URL was served from cache
     */
    private void publishAnalytics(String shortCode, HttpServletRequest request, boolean fromCache) {
        try {
            UrlAnalytics analytics = buildAnalyticsEvent(shortCode, request);
            analyticsEventPublisher.publishClickEvent(analytics);
            logger.debug("Published analytics event for short code: {} (fromCache: {})", 
                    shortCode, fromCache);
        } catch (Exception e) {
            // Analytics failure is non-critical
            // Log warning but don't fail the request
            logger.warn("Failed to publish analytics event for short code {}: {}", 
                    shortCode, e.getMessage());
        }
    }

    /**
     * Builds an analytics event from the HTTP request.
     * 
     * Extracts:
     * - IP address
     * - User-Agent (browser, OS, device)
     * - Referer
     * - Timestamp
     * 
     * Note: Device type, country, browser, OS would be enriched
     * by the analytics consumer (not done here for performance).
     * 
     * @param shortCode the short code
     * @param request HTTP request
     * @return UrlAnalytics entity (not persisted yet)
     */
    private UrlAnalytics buildAnalyticsEvent(String shortCode, HttpServletRequest request) {
        UrlAnalytics analytics = new UrlAnalytics();
        analytics.setShortCode(shortCode);
        analytics.setClickTimestamp(LocalDateTime.now());
        analytics.setClickDate(LocalDateTime.now().toLocalDate());
        
        // Extract IP address
        String ipAddress = getClientIpAddress(request);
        analytics.setIpAddress(ipAddress);
        
        // Extract User-Agent
        String userAgent = request.getHeader("User-Agent");
        analytics.setUserAgent(userAgent);
        
        // Extract Referer
        String referer = request.getHeader("Referer");
        analytics.setReferrer(referer);
        
        // Device type, country, browser, OS would be enriched by consumer
        // Not done here to keep redirect fast
        
        return analytics;
    }

    /**
     * Extracts the client IP address from the HTTP request.
     * 
     * Handles proxy headers (X-Forwarded-For, X-Real-IP) for
     * applications behind load balancers or reverse proxies.
     * 
     * @param request HTTP request
     * @return client IP address
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        
        // X-Forwarded-For can contain multiple IPs (client, proxy1, proxy2)
        // Take the first one (original client)
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        
        return ip;
    }

    // Custom exceptions for different error scenarios

    /**
     * Exception thrown when a short code is not found.
     */
    public static class UrlNotFoundException extends RuntimeException {
        public UrlNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when a short code has expired.
     */
    public static class UrlExpiredException extends RuntimeException {
        public UrlExpiredException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when a short code is inactive.
     */
    public static class UrlInactiveException extends RuntimeException {
        public UrlInactiveException(String message) {
            super(message);
        }
    }
}

