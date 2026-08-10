package com.shortener.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Redis-based rate limiter using sliding window algorithm.
 * 
 * This implementation uses Redis for distributed rate limiting across
 * multiple application instances. It's fast, atomic, and consistent.
 * 
 * Algorithm: Sliding Window Log
 * 
 * For each identifier (IP or user), we maintain a sorted set in Redis
 * containing timestamps of recent requests. When a request comes in:
 * 1. Remove timestamps outside the current window
 * 2. Count remaining timestamps
 * 3. If count < limit, add current timestamp and allow request
 * 4. If count >= limit, reject request
 * 
 * Why Rate Limiting is Critical:
 * 
 * 1. Abuse Prevention:
 *    - Prevents malicious users from overwhelming the system
 *    - Protects against DDoS attacks
 *    - Prevents API abuse and scraping
 * 
 * 2. Resource Protection:
 *    - Ensures fair usage of system resources
 *    - Prevents one user from consuming all capacity
 *    - Protects database and cache from overload
 * 
 * 3. Cost Control:
 *    - Limits infrastructure costs
 *    - Prevents runaway usage
 *    - Enables predictable scaling
 * 
 * 4. Service Stability:
 *    - Maintains service availability for all users
 *    - Prevents cascading failures
 *    - Ensures consistent performance
 * 
 * Why Redis is Used:
 * 
 * 1. Atomic Operations:
 *    - Redis operations are atomic
 *    - No race conditions in distributed systems
 *    - Consistent across multiple application instances
 * 
 * 2. Performance:
 *    - In-memory operations (< 1ms)
 *    - Much faster than database queries
 *    - Doesn't block request processing
 * 
 * 3. Distributed:
 *    - Works across multiple servers
 *    - Shared state for all application instances
 *    - No need for sticky sessions
 * 
 * 4. Built-in TTL:
 *    - Automatic expiration of old data
 *    - No manual cleanup needed
 *    - Memory efficient
 * 
 * Failure Handling (Redis Down):
 * 
 * Strategy: Fail Open (Allow requests if Redis is unavailable)
 * 
 * Rationale:
 * - Rate limiting is a protective measure, not core functionality
 * - Better to allow some abuse than to deny all legitimate users
 * - Can be changed to "fail closed" for stricter security
 * 
 * Implementation:
 * - Try-catch around Redis operations
 * - Log warnings but don't throw exceptions
 * - Return true (allow request) on Redis failure
 * - Monitor Redis health separately
 * 
 * Alternative: Fail Closed
 * - Return false (deny request) on Redis failure
 * - More secure but can cause service outage
 * - Use circuit breaker pattern
 */
@Service
public class RedisRateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RedisRateLimiter.class);

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Default rate limit for shorten endpoint (requests per minute).
     * Configurable via: url-shortener.rate-limit.shorten.requests-per-minute
     */
    @Value("${url-shortener.rate-limit.shorten.requests-per-minute:100}")
    private int shortenRequestsPerMinute;

    /**
     * Default rate limit for redirect endpoint (requests per minute).
     * Redirects are typically more frequent, so higher limit.
     * Configurable via: url-shortener.rate-limit.redirect.requests-per-minute
     */
    @Value("${url-shortener.rate-limit.redirect.requests-per-minute:1000}")
    private int redirectRequestsPerMinute;

    /**
     * Default window duration in seconds.
     * Configurable via: url-shortener.rate-limit.window-seconds
     */
    @Value("${url-shortener.rate-limit.window-seconds:60}")
    private int windowSeconds;

    /**
     * Redis key prefix for rate limiting.
     * Format: "rate:ip:{ip}" or "rate:user:{userId}"
     */
    private static final String RATE_LIMIT_KEY_PREFIX = "rate:";

    /**
     * Lua script for atomic sliding window rate limiting.
     * 
     * This script:
     * 1. Removes entries older than the window
     * 2. Counts current entries
     * 3. If under limit, adds current timestamp
     * 4. Returns remaining count and whether request is allowed
     * 
     * Why Lua script:
     * - Atomic execution (no race conditions)
     * - Single round-trip to Redis
     * - Better performance than multiple Redis calls
     */
    private static final String RATE_LIMIT_SCRIPT = 
        "local key = KEYS[1]\n" +
        "local window = tonumber(ARGV[1])\n" +
        "local limit = tonumber(ARGV[2])\n" +
        "local now = tonumber(ARGV[3])\n" +
        "local windowStart = now - window\n" +
        "\n" +
        "-- Remove entries outside the window\n" +
        "redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)\n" +
        "\n" +
        "-- Count current entries\n" +
        "local count = redis.call('ZCARD', key)\n" +
        "\n" +
        "if count < limit then\n" +
        "    -- Add current request timestamp\n" +
        "    redis.call('ZADD', key, now, now)\n" +
        "    -- Set expiration to window duration (cleanup)\n" +
        "    redis.call('EXPIRE', key, window)\n" +
        "    -- Return: allowed=true, remaining=limit-count-1\n" +
        "    return {1, limit - count - 1}\n" +
        "else\n" +
        "    -- Return: allowed=false, remaining=0\n" +
        "    return {0, 0}\n" +
        "end";

    private final RedisScript<List> rateLimitScript;

    public RedisRateLimiter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = RedisScript.of(
                RATE_LIMIT_SCRIPT,
                List.class
        );
    }

    /**
     * Checks if a request should be allowed based on rate limit.
     * 
     * Uses sliding window algorithm with Redis sorted sets.
     * 
     * @param identifier the identifier to rate limit (IP address or user ID)
     * @param limit the maximum number of requests allowed in the window
     * @param windowSeconds the time window in seconds
     * @return RateLimitResult with allowed status and remaining requests
     */
    public RateLimitResult checkRateLimit(String identifier, int limit, int windowSeconds) {
        try {
            String key = RATE_LIMIT_KEY_PREFIX + identifier;
            long now = System.currentTimeMillis() / 1000; // Current timestamp in seconds

            // Execute Lua script atomically
            @SuppressWarnings("unchecked")
            List<Long> result = (List<Long>) redisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(windowSeconds),
                    String.valueOf(limit),
                    String.valueOf(now)
            );

            if (result == null || result.isEmpty()) {
                // Redis error - fail open (allow request)
                logger.warn("Rate limit check failed for identifier: {}, allowing request", identifier);
                return new RateLimitResult(true, limit);
            }

            boolean allowed = result.get(0) == 1L;
            long remaining = result.size() > 1 ? result.get(1) : 0L;

            if (!allowed) {
                logger.debug("Rate limit exceeded for identifier: {} (limit: {}/{}s)", 
                        identifier, limit, windowSeconds);
            }

            return new RateLimitResult(allowed, remaining);

        } catch (Exception e) {
            // Redis down or error - fail open (allow request)
            // This prevents rate limiting from causing service outages
            logger.warn("Rate limit check failed for identifier: {} due to Redis error: {}. Allowing request.", 
                    identifier, e.getMessage());
            return new RateLimitResult(true, limit);
        }
    }

    /**
     * Checks rate limit for IP address.
     * 
     * @param ipAddress the IP address
     * @param limit the maximum number of requests allowed
     * @param windowSeconds the time window in seconds
     * @return RateLimitResult
     */
    public RateLimitResult checkRateLimitByIp(String ipAddress, int limit, int windowSeconds) {
        String identifier = "ip:" + ipAddress;
        return checkRateLimit(identifier, limit, windowSeconds);
    }

    /**
     * Checks rate limit for user ID.
     * 
     * @param userId the user ID
     * @param limit the maximum number of requests allowed
     * @param windowSeconds the time window in seconds
     * @return RateLimitResult
     */
    public RateLimitResult checkRateLimitByUser(Long userId, int limit, int windowSeconds) {
        String identifier = "user:" + userId;
        return checkRateLimit(identifier, limit, windowSeconds);
    }

    /**
     * Checks rate limit for shorten endpoint (per IP).
     * 
     * @param ipAddress the IP address
     * @return RateLimitResult
     */
    public RateLimitResult checkShortenRateLimit(String ipAddress) {
        return checkRateLimitByIp(ipAddress, shortenRequestsPerMinute, windowSeconds);
    }

    /**
     * Checks rate limit for redirect endpoint (per IP).
     * 
     * @param ipAddress the IP address
     * @return RateLimitResult
     */
    public RateLimitResult checkRedirectRateLimit(String ipAddress) {
        return checkRateLimitByIp(ipAddress, redirectRequestsPerMinute, windowSeconds);
    }

    /**
     * Result of a rate limit check.
     */
    public static class RateLimitResult {
        private final boolean allowed;
        private final long remaining;

        public RateLimitResult(boolean allowed, long remaining) {
            this.allowed = allowed;
            this.remaining = remaining;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public long getRemaining() {
            return remaining;
        }
    }
}

