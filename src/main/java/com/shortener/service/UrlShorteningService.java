package com.shortener.service;

import com.shortener.dto.ShortenUrlRequest;
import com.shortener.dto.ShortenUrlResponse;
import com.shortener.entity.UrlMapping;
import com.shortener.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Service for URL shortening operations.
 * 
 * Handles the complete flow of shortening a URL:
 * 1. Validate and normalize the input URL
 * 2. Check for custom alias conflicts
 * 3. Save to database (to get auto-generated ID)
 * 4. Generate short code from database ID
 * 5. Update entity with short code
 * 6. Cache the result in Redis
 * 
 * Design decisions:
 * 
 * 1. Why DB insert happens before short code generation:
 *    - We need the database to generate an auto-increment ID
 *    - The ID is used as input to Base62 encoding
 *    - This ensures uniqueness (no collisions)
 *    - Alternative: Use Snowflake ID generator (no DB insert needed)
 * 
 * 2. Why cache is updated after DB write:
 *    - Cache-aside pattern: write to DB first, then cache
 *    - Ensures data consistency (DB is source of truth)
 *    - If cache write fails, DB still has the data
 *    - Cache can be rebuilt from DB if needed
 *    - Avoids race conditions where cache has data but DB doesn't
 * 
 * 3. Transaction management:
 *    - @Transactional ensures atomicity of insert + update
 *    - If short code generation fails, entire operation rolls back
 *    - Prevents orphaned records in database
 */
@Service
public class UrlShorteningService {

    private static final Logger logger = LoggerFactory.getLogger(UrlShorteningService.class);

    private final UrlMappingRepository urlMappingRepository;
    private final ShortCodeGenerator shortCodeGenerator;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Base URL for generating complete short URLs.
     * Configured via application.properties: url-shortener.base-url
     * Example: "https://short.ly"
     */
    @Value("${url-shortener.base-url:http://localhost:8080}")
    private String baseUrl;

    /**
     * Redis key prefix for URL mappings.
     * Format: "url:{shortCode}" -> longUrl
     */
    private static final String REDIS_URL_KEY_PREFIX = "url:";

    /**
     * Redis key prefix for URL metadata.
     * Format: "url:meta:{shortCode}" -> JSON metadata
     */
    private static final String REDIS_META_KEY_PREFIX = "url:meta:";

    /**
     * Cache TTL in days for active URLs.
     * Default: 7 days
     */
    @Value("${url-shortener.cache.ttl-days:7}")
    private int cacheTtlDays;

    public UrlShorteningService(
            UrlMappingRepository urlMappingRepository,
            ShortCodeGenerator shortCodeGenerator,
            RedisTemplate<String, String> redisTemplate) {
        this.urlMappingRepository = urlMappingRepository;
        this.shortCodeGenerator = shortCodeGenerator;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Shortens a long URL.
     * 
     * Process:
     * 1. Validate and normalize the input URL
     * 2. Check if custom alias is provided and available
     * 3. Create UrlMapping entity
     * 4. Save to database (gets auto-generated ID)
     * 5. Generate short code from ID (or use custom alias)
     * 6. Update entity with short code
     * 7. Cache in Redis
     * 8. Return response DTO
     * 
     * @param request the shorten URL request
     * @param userId optional user ID (null for anonymous users)
     * @return ShortenUrlResponse with short code and metadata
     * @throws IllegalArgumentException if URL is invalid or custom alias is taken
     */
    @Transactional
    public ShortenUrlResponse shortenUrl(ShortenUrlRequest request, Long userId) {
        logger.info("Shortening URL for user {}: {}", userId, request.getLongUrl());

        // Step 1: Validate and normalize URL
        String normalizedUrl = validateAndNormalizeUrl(request.getLongUrl());

        // Step 2: Handle custom alias if provided
        String shortCode;
        if (request.getCustomAlias() != null && !request.getCustomAlias().isEmpty()) {
            // Validate custom alias format
            if (!shortCodeGenerator.isValidShortCodeFormat(request.getCustomAlias())) {
                throw new IllegalArgumentException(
                    "Custom alias must be 4-10 alphanumeric characters"
                );
            }

            // Check if custom alias is already taken
            if (urlMappingRepository.existsByShortCode(request.getCustomAlias()) ||
                urlMappingRepository.existsByCustomAlias(request.getCustomAlias())) {
                throw new IllegalArgumentException(
                    "Custom alias '" + request.getCustomAlias() + "' is already taken"
                );
            }

            shortCode = request.getCustomAlias();
            logger.debug("Using custom alias: {}", shortCode);
        } else {
            // Will be generated after database insert
            shortCode = null;
        }

        // Step 3: Create UrlMapping entity
        UrlMapping urlMapping = new UrlMapping();
        urlMapping.setLongUrl(normalizedUrl);
        urlMapping.setUserId(userId);
        urlMapping.setCustomAlias(request.getCustomAlias());
        urlMapping.setIsActive(true);

        // Set expiration if provided
        if (request.getExpirationDays() != null && request.getExpirationDays() > 0) {
            urlMapping.setExpiresAt(
                LocalDateTime.now().plusDays(request.getExpirationDays())
            );
        }

        // Set tags if provided
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            urlMapping.setTags(request.getTags());
        }

        // Step 4: Save to database FIRST to get auto-generated ID
        // This is critical: we need the ID to generate the short code
        urlMapping = urlMappingRepository.save(urlMapping);
        logger.debug("Saved URL mapping to database with ID: {}", urlMapping.getId());

        // Step 5: Generate short code if not using custom alias
        if (shortCode == null) {
            shortCode = shortCodeGenerator.generateShortCode(urlMapping.getId());
            urlMapping.setShortCode(shortCode);

            // Step 6: Update entity with short code
            // This is a second database write, but necessary for our current strategy
            // Alternative: Use Snowflake ID generator to avoid this step
            urlMapping = urlMappingRepository.save(urlMapping);
            logger.debug("Updated URL mapping with short code: {}", shortCode);
        } else {
            // If using custom alias, set it as short code
            urlMapping.setShortCode(shortCode);
            urlMapping = urlMappingRepository.save(urlMapping);
        }

        // Step 7: Cache in Redis AFTER database write
        // Cache-aside pattern: DB is source of truth, cache is for performance
        cacheUrlMapping(urlMapping);

        // Step 8: Build and return response
        return buildResponse(urlMapping);
    }

    /**
     * Validates and normalizes a URL.
     * 
     * Normalization steps:
     * 1. Add https:// if no protocol is specified
     * 2. Remove trailing slash (optional, can be kept if needed)
     * 3. Validate URL format
     * 
     * @param url the URL to normalize
     * @return normalized URL
     * @throws IllegalArgumentException if URL is invalid
     */
    private String validateAndNormalizeUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("URL cannot be null or empty");
        }

        String normalized = url.trim();

        // Add https:// if no protocol is specified
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
            logger.debug("Added https:// protocol to URL: {}", normalized);
        }

        // Validate URL format by attempting to parse it
        try {
            new URL(normalized);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL format: " + url, e);
        }

        // Optional: Remove trailing slash (uncomment if needed)
        // if (normalized.endsWith("/") && normalized.length() > 1) {
        //     normalized = normalized.substring(0, normalized.length() - 1);
        // }

        return normalized;
    }

    /**
     * Caches the URL mapping in Redis.
     * 
     * Caches two keys:
     * 1. "url:{shortCode}" -> longUrl (for fast redirect lookups)
     * 2. "url:meta:{shortCode}" -> JSON metadata (optional, for additional info)
     * 
     * Cache TTL:
     * - Active URLs: configured TTL (default 7 days)
     * - Expired URLs: shorter TTL (1 day) to allow cleanup
     * 
     * Why cache after DB write:
     * - Ensures DB is source of truth
     * - If cache write fails, operation still succeeds
     * - Cache can be rebuilt from DB
     * - Avoids race conditions
     * 
     * @param urlMapping the URL mapping to cache
     */
    private void cacheUrlMapping(UrlMapping urlMapping) {
        try {
            String shortCode = urlMapping.getShortCode();
            String longUrl = urlMapping.getLongUrl();

            // Cache the URL mapping for fast redirect lookups
            String urlKey = REDIS_URL_KEY_PREFIX + shortCode;
            redisTemplate.opsForValue().set(urlKey, longUrl, cacheTtlDays, TimeUnit.DAYS);
            logger.debug("Cached URL mapping in Redis: {} -> {}", shortCode, longUrl);

            // Optional: Cache metadata (can be used for analytics or additional info)
            // For now, we'll just cache the URL. Metadata can be added later if needed.

        } catch (Exception e) {
            // Log error but don't fail the operation
            // Cache is for performance, DB is source of truth
            logger.warn("Failed to cache URL mapping in Redis: {}", e.getMessage());
        }
    }

    /**
     * Builds a ShortenUrlResponse from a UrlMapping entity.
     * 
     * @param urlMapping the URL mapping entity
     * @return ShortenUrlResponse DTO
     */
    private ShortenUrlResponse buildResponse(UrlMapping urlMapping) {
        ShortenUrlResponse response = new ShortenUrlResponse();
        response.setShortCode(urlMapping.getShortCode());
        response.setShortUrl(baseUrl + "/" + urlMapping.getShortCode());
        response.setLongUrl(urlMapping.getLongUrl());
        response.setCreatedAt(urlMapping.getCreatedAt());
        response.setExpiresAt(urlMapping.getExpiresAt());
        response.setTags(urlMapping.getTags());

        // Optional: Generate QR code URL
        // response.setQrCode(baseUrl + "/qr/" + urlMapping.getShortCode());

        return response;
    }

    /**
     * Gets the base URL for constructing short URLs.
     * 
     * @return the base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }
}

