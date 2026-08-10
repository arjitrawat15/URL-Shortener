package com.shortener.controller;

import com.shortener.dto.ErrorResponse;
import com.shortener.service.RedisRateLimiter;
import com.shortener.service.UrlRedirectService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for URL redirection (READ PATH).
 * 
 * This is the most critical endpoint - it will be called millions of times per day.
 * Performance is paramount: every millisecond counts.
 * 
 * Endpoints:
 * - GET /{shortCode} - Redirect to long URL
 * 
 * HTTP Status Codes:
 * - 302 Found - Successful redirect
 * - 400 Bad Request - Invalid short code format
 * - 404 Not Found - Short code doesn't exist
 * - 410 Gone - Short code expired or inactive
 * - 429 Too Many Requests - Rate limit exceeded
 * - 503 Service Unavailable - Database unavailable (cache miss)
 */
@RestController
public class UrlRedirectController {

    private static final Logger logger = LoggerFactory.getLogger(UrlRedirectController.class);

    private final UrlRedirectService urlRedirectService;
    private final RedisRateLimiter rateLimiter;

    public UrlRedirectController(UrlRedirectService urlRedirectService,
                                 RedisRateLimiter rateLimiter) {
        this.urlRedirectService = urlRedirectService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Redirects a short code to its corresponding long URL.
     * 
     * This is the READ PATH - the most frequently called endpoint.
     * It must be extremely fast (< 10ms for cache hits).
     * 
     * Flow:
     * 1. Check rate limit (per IP)
     * 2. Extract short code from path
     * 3. Call service to resolve short code
     * 4. Return HTTP 302 redirect with Location header
     * 
     * Performance considerations:
     * - Cache hits should be < 1ms
     * - Cache misses should be < 50ms (DB query)
     * - Analytics and counters are async (non-blocking)
     * - Rate limiting adds < 1ms (Redis operation)
     * 
     * @param shortCode the short code from the URL path
     * @param request HTTP request (for analytics and IP extraction)
     * @return HTTP 302 redirect response
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode, 
                                         HttpServletRequest request) {
        
        logger.debug("Redirect request for short code: {}", shortCode);

        // Step 1: Check rate limit (per IP)
        String clientIp = getClientIpAddress(request);
        RedisRateLimiter.RateLimitResult rateLimitResult = 
                rateLimiter.checkRedirectRateLimit(clientIp);

        if (!rateLimitResult.isAllowed()) {
            logger.warn("Rate limit exceeded for IP: {} on redirect endpoint", clientIp);
            throw new RateLimitExceededException(
                    "Rate limit exceeded. Please try again later.",
                    rateLimitResult.getRemaining()
            );
        }

        try {
            // Step 2: Resolve short code to long URL
            // This handles cache lookup, DB fallback, validation, and analytics
            String longUrl = urlRedirectService.resolveShortCode(shortCode, request);

            // Step 3: Return HTTP 302 Found with Location header
            // This is the standard redirect response
            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(java.net.URI.create(longUrl));
            
            // Add rate limit headers (optional, for client awareness)
            headers.add("X-RateLimit-Remaining", String.valueOf(rateLimitResult.getRemaining()));
            
            logger.debug("Redirecting {} -> {}", shortCode, longUrl);
            
            return ResponseEntity.status(HttpStatus.FOUND)
                    .headers(headers)
                    .build();

        } catch (IllegalArgumentException e) {
            // Invalid short code format
            logger.warn("Invalid short code format: {}", shortCode);
            throw e; // Will be handled by @ExceptionHandler

        } catch (UrlRedirectService.UrlNotFoundException e) {
            // Short code doesn't exist
            logger.warn("Short code not found: {}", shortCode);
            throw e; // Will be handled by @ExceptionHandler

        } catch (UrlRedirectService.UrlExpiredException e) {
            // Short code has expired
            logger.warn("Short code expired: {}", shortCode);
            throw e; // Will be handled by @ExceptionHandler

        } catch (UrlRedirectService.UrlInactiveException e) {
            // Short code is inactive
            logger.warn("Short code inactive: {}", shortCode);
            throw e; // Will be handled by @ExceptionHandler

        } catch (Exception e) {
            // Unexpected error
            logger.error("Unexpected error redirecting short code: {}", shortCode, e);
            throw e; // Will be handled by @ExceptionHandler
        }
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
        
        return ip != null ? ip : "unknown";
    }

    /**
     * Handles invalid short code format errors.
     * 
     * Returns 400 Bad Request.
     * 
     * @param ex the exception
     * @return ErrorResponse
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex) {

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles short code not found errors.
     * 
     * Returns 404 Not Found.
     * 
     * @param ex the exception
     * @return ErrorResponse
     */
    @ExceptionHandler(UrlRedirectService.UrlNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUrlNotFoundException(
            UrlRedirectService.UrlNotFoundException ex) {

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    /**
     * Handles expired or inactive URL errors.
     * 
     * Returns 410 Gone (resource no longer available).
     * 
     * @param ex the exception
     * @return ErrorResponse
     */
    @ExceptionHandler({
            UrlRedirectService.UrlExpiredException.class,
            UrlRedirectService.UrlInactiveException.class
    })
    public ResponseEntity<ErrorResponse> handleUrlUnavailableException(
            RuntimeException ex) {

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.GONE.value(),
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.GONE).body(errorResponse);
    }

    /**
     * Handles rate limit exceeded errors.
     * 
     * Returns 429 Too Many Requests.
     * 
     * @param ex the exception
     * @return ErrorResponse with rate limit information
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(
            RateLimitExceededException ex) {

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("X-RateLimit-Remaining", String.valueOf(ex.getRemaining()))
                .header("Retry-After", "60") // Suggest retry after 60 seconds
                .body(errorResponse);
    }

    /**
     * Handles unexpected errors.
     * 
     * Returns 503 Service Unavailable if it's a database issue,
     * otherwise 500 Internal Server Error.
     * 
     * @param ex the exception
     * @return ErrorResponse
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        logger.error("Unexpected error in redirect controller", ex);

        // Check if it's a database connectivity issue
        // In production, you might want more sophisticated error detection
        boolean isDatabaseError = ex.getMessage() != null && 
                (ex.getMessage().contains("database") || 
                 ex.getMessage().contains("connection") ||
                 ex.getMessage().contains("timeout"));

        HttpStatus status = isDatabaseError 
                ? HttpStatus.SERVICE_UNAVAILABLE 
                : HttpStatus.INTERNAL_SERVER_ERROR;

        ErrorResponse errorResponse = new ErrorResponse(
                status.value(),
                isDatabaseError 
                        ? "Service temporarily unavailable. Please try again later."
                        : "An unexpected error occurred. Please try again later."
        );

        return ResponseEntity.status(status).body(errorResponse);
    }

    /**
     * Custom exception for rate limit exceeded.
     */
    public static class RateLimitExceededException extends RuntimeException {
        private final long remaining;

        public RateLimitExceededException(String message, long remaining) {
            super(message);
            this.remaining = remaining;
        }

        public long getRemaining() {
            return remaining;
        }
    }
}




