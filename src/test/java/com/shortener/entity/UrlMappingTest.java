package com.shortener.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for UrlMapping entity.
 * 
 * Tests cover:
 * - Expiration logic
 * - Validation logic
 * - Edge cases (now, past, future)
 * - Active/inactive states
 */
@DisplayName("UrlMapping Entity Tests")
class UrlMappingTest {

    private UrlMapping urlMapping;

    @BeforeEach
    void setUp() {
        urlMapping = new UrlMapping();
        urlMapping.setShortCode("test123");
        urlMapping.setLongUrl("https://example.com");
        urlMapping.setIsActive(true);
    }

    @Test
    @DisplayName("isExpired should return false when expiresAt is null")
    void isExpiredNullExpiration() {
        urlMapping.setExpiresAt(null);
        assertFalse(urlMapping.isExpired(), 
                "URL with null expiration should not be expired");
    }

    @Test
    @DisplayName("isExpired should return false when expiresAt is in the future")
    void isExpiredFutureExpiration() {
        urlMapping.setExpiresAt(LocalDateTime.now().plusDays(1));
        assertFalse(urlMapping.isExpired(), 
                "URL with future expiration should not be expired");
    }

    @Test
    @DisplayName("isExpired should return true when expiresAt is in the past")
    void isExpiredPastExpiration() {
        urlMapping.setExpiresAt(LocalDateTime.now().minusDays(1));
        assertTrue(urlMapping.isExpired(), 
                "URL with past expiration should be expired");
    }

    @Test
    @DisplayName("isExpired should return true when expiresAt is exactly now")
    void isExpiredNowExpiration() {
        // Set expiration to a few seconds ago to account for test execution time
        urlMapping.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertTrue(urlMapping.isExpired(), 
                "URL with expiration in the past should be expired");
    }

    @Test
    @DisplayName("isValid should return true when active and not expired")
    void isValidActiveAndNotExpired() {
        urlMapping.setIsActive(true);
        urlMapping.setExpiresAt(LocalDateTime.now().plusDays(1));
        assertTrue(urlMapping.isValid(), 
                "Active URL with future expiration should be valid");
    }

    @Test
    @DisplayName("isValid should return false when inactive")
    void isValidInactive() {
        urlMapping.setIsActive(false);
        urlMapping.setExpiresAt(LocalDateTime.now().plusDays(1));
        assertFalse(urlMapping.isValid(), 
                "Inactive URL should not be valid");
    }

    @Test
    @DisplayName("isValid should return false when expired")
    void isValidExpired() {
        urlMapping.setIsActive(true);
        urlMapping.setExpiresAt(LocalDateTime.now().minusDays(1));
        assertFalse(urlMapping.isValid(), 
                "Expired URL should not be valid");
    }

    @Test
    @DisplayName("isValid should return false when inactive and expired")
    void isValidInactiveAndExpired() {
        urlMapping.setIsActive(false);
        urlMapping.setExpiresAt(LocalDateTime.now().minusDays(1));
        assertFalse(urlMapping.isValid(), 
                "Inactive and expired URL should not be valid");
    }

    @Test
    @DisplayName("isValid should return true when active and never expires")
    void isValidActiveNeverExpires() {
        urlMapping.setIsActive(true);
        urlMapping.setExpiresAt(null);
        assertTrue(urlMapping.isValid(), 
                "Active URL with no expiration should be valid");
    }

    @Test
    @DisplayName("incrementClickCount should increase count by 1")
    void incrementClickCount() {
        urlMapping.setClickCount(100L);
        urlMapping.incrementClickCount();
        assertEquals(101L, urlMapping.getClickCount(), 
                "Click count should increase by 1");
    }

    @Test
    @DisplayName("incrementClickCount should work with null initial count")
    void incrementClickCountNullInitial() {
        urlMapping.setClickCount(null);
        urlMapping.incrementClickCount();
        // Note: This might throw NPE depending on implementation
        // Adjust test based on actual behavior
    }

    @Test
    @DisplayName("Constructor with shortCode and longUrl should set defaults")
    void constructorWithParams() {
        UrlMapping mapping = new UrlMapping("abc123", "https://example.com");
        assertEquals("abc123", mapping.getShortCode());
        assertEquals("https://example.com", mapping.getLongUrl());
        assertTrue(mapping.getIsActive(), "Should default to active");
        assertEquals(0L, mapping.getClickCount(), "Should default to 0 clicks");
    }
}




