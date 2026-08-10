package com.shortener.controller;

import com.shortener.dto.ErrorResponse;
import com.shortener.dto.ShortenUrlRequest;
import com.shortener.dto.ShortenUrlResponse;
import com.shortener.service.RedisRateLimiter;
import com.shortener.service.UrlShorteningService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller for URL shortening operations.
 * 
 * Provides the API endpoint for creating short URLs from long URLs.
 * 
 * Endpoints:
 * - POST /api/v1/urls/shorten - Create a short URL
 * 
 * Authentication:
 * - Currently supports anonymous users (userId = null)
 * - In production, extract userId from JWT token or session
 */
@RestController
@RequestMapping("/api/v1/urls")
public class UrlShorteningController {

    private static final Logger logger = LoggerFactory.getLogger(UrlShorteningController.class);

    private final UrlShorteningService urlShorteningService;
    private final RedisRateLimiter rateLimiter;

    public UrlShorteningController(UrlShorteningService urlShorteningService,
                                   RedisRateLimiter rateLimiter) {
        this.urlShorteningService = urlShorteningService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Creates a short URL from a long URL.
     * 
     * Request flow:
     * 1. Check rate limit (per IP)
     * 2. Validate request body (via @Valid)
     * 3. Extract user ID from authentication context (currently null for anonymous)
     * 4. Call service to shorten URL
     * 5. Return response with short code and metadata
     * 
     * HTTP Status Codes:
     * - 201 Created: URL successfully shortened
     * - 400 Bad Request: Invalid input (validation errors)
     * - 409 Conflict: Custom alias already exists (handled in service)
     * - 429 Too Many Requests: Rate limit exceeded
     * - 500 Internal Server Error: Unexpected server error
     * 
     * @param request the shorten URL request
     * @param httpRequest HTTP request (for IP extraction)
     * @return ShortenUrlResponse with short code and metadata
     */
    @PostMapping("/shorten")
    public ResponseEntity<ShortenUrlResponse> shortenUrl(
            @Valid @RequestBody ShortenUrlRequest request,
            HttpServletRequest httpRequest) {

        logger.info("Received shorten URL request: {}", request.getLongUrl());

        // Step 1: Check rate limit (per IP)
        String clientIp = getClientIpAddress(httpRequest);
        RedisRateLimiter.RateLimitResult rateLimitResult = 
                rateLimiter.checkShortenRateLimit(clientIp);

        if (!rateLimitResult.isAllowed()) {
            logger.warn("Rate limit exceeded for IP: {} on shorten endpoint", clientIp);
            throw new RateLimitExceededException(
                    "Rate limit exceeded. Please try again later.",
                    rateLimitResult.getRemaining()
            );
        }

        try {
            // TODO: Extract userId from authentication context
            // For now, support anonymous users (userId = null)
            Long userId = getCurrentUserId();

            ShortenUrlResponse response = urlShorteningService.shortenUrl(request, userId);

            logger.info("Successfully shortened URL: {} -> {}", 
                    request.getLongUrl(), response.getShortCode());

            // Add rate limit headers (optional, for client awareness)
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header("X-RateLimit-Remaining", String.valueOf(rateLimitResult.getRemaining()))
                    .body(response);

        } catch (IllegalArgumentException e) {
            // Handle validation errors and custom alias conflicts
            logger.warn("Invalid request: {}", e.getMessage());
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
     * Gets the current user ID from authentication context.
     * 
     * TODO: Implement authentication integration
     * - Extract from JWT token
     * - Extract from Spring Security context
     * - Return null for anonymous users
     * 
     * @return user ID or null for anonymous users
     */
    private Long getCurrentUserId() {
        // Placeholder: In production, extract from SecurityContext
        // Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // if (authentication != null && authentication.isAuthenticated()) {
        //     return ((UserPrincipal) authentication.getPrincipal()).getId();
        // }
        return null; // Anonymous user
    }

    /**
     * Handles validation errors from @Valid annotation.
     * 
     * Returns 400 Bad Request with detailed field-level error messages.
     * 
     * @param ex the validation exception
     * @return ErrorResponse with validation errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        logger.warn("Validation error: {}", ex.getMessage());

        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ErrorResponse.FieldError(
                        error.getField(),
                        error.getDefaultMessage(),
                        error.getRejectedValue()
                ))
                .collect(Collectors.toList());

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed"
        );
        errorResponse.setErrors(fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles IllegalArgumentException (invalid URL, custom alias conflicts, etc.).
     * 
     * Returns 400 Bad Request for invalid input.
     * Returns 409 Conflict for custom alias conflicts.
     * 
     * @param ex the illegal argument exception
     * @return ErrorResponse with error message
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex) {

        logger.warn("Invalid argument: {}", ex.getMessage());

        // Check if it's a custom alias conflict
        int status = ex.getMessage().contains("already taken") 
                ? HttpStatus.CONFLICT.value() 
                : HttpStatus.BAD_REQUEST.value();

        ErrorResponse errorResponse = new ErrorResponse(
                status,
                ex.getMessage()
        );

        return ResponseEntity.status(status).body(errorResponse);
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
     * Handles all other exceptions (unexpected errors).
     * 
     * Returns 500 Internal Server Error.
     * In production, log full stack trace but don't expose it to client.
     * 
     * @param ex the exception
     * @return ErrorResponse with generic error message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        logger.error("Unexpected error while shortening URL", ex);

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred. Please try again later."
        );

        // Only include details in development
        // In production, set details to null or use a flag
        // errorResponse.setDetails(ex.getMessage());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
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

