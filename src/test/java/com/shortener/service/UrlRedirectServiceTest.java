package com.shortener.service;

import com.shortener.entity.UrlMapping;
import com.shortener.repository.UrlMappingRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CRITICAL: Unit tests for UrlRedirectService.
 * 
 * These tests verify the most critical read path that handles millions of requests.
 * 
 * Tests cover:
 * - Cache hit scenarios (fast path)
 * - Cache miss with DB fallback
 * - Expired/inactive URL handling
 * - Failure scenarios (Redis down, DB down)
 * - Non-blocking analytics and counters
 * - No DB writes on redirect path
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UrlRedirectService Tests (Critical Read Path)")
class UrlRedirectServiceTest {

    @Mock
    private UrlMappingRepository urlMappingRepository;

    @Mock
    private ShortCodeGenerator shortCodeGenerator;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AnalyticsEventPublisher analyticsEventPublisher;

    @Mock
    private HttpServletRequest httpRequest;

    @InjectMocks
    private UrlRedirectService urlRedirectService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(httpRequest.getHeader("User-Agent")).thenReturn("Mozilla/5.0");
        when(httpRequest.getHeader("Referer")).thenReturn("https://twitter.com");
        when(httpRequest.getRemoteAddr()).thenReturn("192.168.1.1");
    }

    @Test
    @DisplayName("Cache hit should return URL immediately without DB call")
    void resolveShortCodeCacheHit() {
        // Arrange
        String shortCode = "4c92";
        String longUrl = "https://example.com";
        
        when(shortCodeGenerator.isValidShortCodeFormat(shortCode)).thenReturn(true);
        when(valueOperations.get("url:4c92")).thenReturn(longUrl);
        
        // Act
        String result = urlRedirectService.resolveShortCode(shortCode, httpRequest);
        
        // Assert
        assertEquals(longUrl, result);
        verify(urlMappingRepository, never()).findByShortCode(anyString());
        verify(urlMappingRepository, never()).findValidByShortCode(anyString(), any());
        verify(analyticsEventPublisher).publishClickEvent(any());
    }

    @Test
    @DisplayName("Cache miss should query database and populate cache")
    void resolveShortCodeCacheMiss() {
        // Arrange
        String shortCode = "4c92";
        String longUrl = "https://example.com";
        
        UrlMapping urlMapping = new UrlMapping();
        urlMapping.setShortCode(shortCode);
        urlMapping.setLongUrl(longUrl);
        urlMapping.setIsActive(true);
        urlMapping.setExpiresAt(null);
        
        when(shortCodeGenerator.isValidShortCodeFormat(shortCode)).thenReturn(true);
        when(valueOperations.get("url:4c92")).thenReturn(null); // Cache miss
        when(urlMappingRepository.findValidByShortCode(eq(shortCode), any(LocalDateTime.class)))
                .thenReturn(Optional.of(urlMapping));
        
        // Act
        String result = urlRedirectService.resolveShortCode(shortCode, httpRequest);
        
        // Assert
        assertEquals(longUrl, result);
        verify(urlMappingRepository).findValidByShortCode(eq(shortCode), any(LocalDateTime.class));
        verify(valueOperations).set(eq("url:4c92"), eq(longUrl), anyLong(), any());
        verify(analyticsEventPublisher).publishClickEvent(any());
    }

    @Test
    @DisplayName("Expired short code should throw UrlExpiredException")
    void resolveShortCodeExpired() {
        String shortCode = "4c92";
        
        UrlMapping expiredMapping = new UrlMapping();
        expiredMapping.setShortCode(shortCode);
        expiredMapping.setIsActive(true);
        expiredMapping.setExpiresAt(LocalDateTime.now().minusDays(1));
        
        when(shortCodeGenerator.isValidShortCodeFormat(shortCode)).thenReturn(true);
        when(valueOperations.get("url:4c92")).thenReturn(null);
        when(urlMappingRepository.findValidByShortCode(eq(shortCode), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(urlMappingRepository.findByShortCode(shortCode))
                .thenReturn(Optional.of(expiredMapping));
        
        assertThrows(
                UrlRedirectService.UrlExpiredException.class,
                () -> urlRedirectService.resolveShortCode(shortCode, httpRequest)
        );
    }

    @Test
    @DisplayName("Inactive short code should throw UrlInactiveException")
    void resolveShortCodeInactive() {
        String shortCode = "4c92";
        
        UrlMapping inactiveMapping = new UrlMapping();
        inactiveMapping.setShortCode(shortCode);
        inactiveMapping.setIsActive(false);
        
        when(shortCodeGenerator.isValidShortCodeFormat(shortCode)).thenReturn(true);
        when(valueOperations.get("url:4c92")).thenReturn(null);
        when(urlMappingRepository.findValidByShortCode(eq(shortCode), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(urlMappingRepository.findByShortCode(shortCode))
                .thenReturn(Optional.of(inactiveMapping));
        
        assertThrows(
                UrlRedirectService.UrlInactiveException.class,
                () -> urlRedirectService.resolveShortCode(shortCode, httpRequest)
        );
    }

    @Test
    @DisplayName("Invalid short code format should throw IllegalArgumentException")
    void resolveShortCodeInvalidFormat() {
        String shortCode = "invalid@code";
        
        when(shortCodeGenerator.isValidShortCodeFormat(shortCode)).thenReturn(false);
        
        assertThrows(
                IllegalArgumentException.class,
                () -> urlRedirectService.resolveShortCode(shortCode, httpRequest)
        );
        
        verify(urlMappingRepository, never()).findByShortCode(anyString());
    }

    @Test
    @DisplayName("Redis down + DB up should succeed (fallback to DB)")
    void resolveShortCodeRedisDownDbUp() {
        String shortCode = "4c92";
        String longUrl = "https://example.com";
        
        UrlMapping urlMapping = new UrlMapping();
        urlMapping.setShortCode(shortCode);
        urlMapping.setLongUrl(longUrl);
        urlMapping.setIsActive(true);
        
        when(shortCodeGenerator.isValidShortCodeFormat(shortCode)).thenReturn(true);
        when(valueOperations.get("url:4c92")).thenThrow(new RuntimeException("Redis down"));
        when(urlMappingRepository.findValidByShortCode(eq(shortCode), any(LocalDateTime.class)))
                .thenReturn(Optional.of(urlMapping));
        
        // Should succeed despite Redis failure
        String result = urlRedirectService.resolveShortCode(shortCode, httpRequest);
        assertEquals(longUrl, result);
    }

    @Test
    @DisplayName("Redis up + DB down + cache hit should succeed")
    void resolveShortCodeRedisUpDbDownCacheHit() {
        String shortCode = "4c92";
        String longUrl = "https://example.com";
        
        when(shortCodeGenerator.isValidShortCodeFormat(shortCode)).thenReturn(true);
        when(valueOperations.get("url:4c92")).thenReturn(longUrl);
        
        // DB is down, but we have cache
        String result = urlRedirectService.resolveShortCode(shortCode, httpRequest);
        assertEquals(longUrl, result);
        verify(urlMappingRepository, never()).findByShortCode(anyString());
    }

    @Test
    @DisplayName("Redis up + DB down + cache miss should throw exception")
    void resolveShortCodeRedisUpDbDownCacheMiss() {
        String shortCode = "4c92";
        
        when(shortCodeGenerator.isValidShortCodeFormat(shortCode)).thenReturn(true);
        when(valueOperations.get("url:4c92")).thenReturn(null);
        when(urlMappingRepository.findValidByShortCode(eq(shortCode), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("Database down"));
        
        // Should propagate exception (would be 503 in controller)
        assertThrows(
                RuntimeException.class,
                () -> urlRedirectService.resolveShortCode(shortCode, httpRequest)
        );
    }

    @Test
    @DisplayName("Redis increment failure should not block redirect")
    void resolveShortCodeRedisIncrementFailure() {
        String shortCode = "4c92";
        String longUrl = "https://example.com";
        
        when(shortCodeGenerator.isValidShortCodeFormat(shortCode)).thenReturn(true);
        when(valueOperations.get("url:4c92")).thenReturn(longUrl);
        when(valueOperations.increment(anyString())).thenThrow(new RuntimeException("Redis error"));
        
        // Should still succeed
        String result = urlRedirectService.resolveShortCode(shortCode, httpRequest);
        assertEquals(longUrl, result);
    }

    @Test
    @DisplayName("Analytics publish failure should not block redirect")
    void resolveShortCodeAnalyticsFailure() {
        String shortCode = "4c92";
        String longUrl = "https://example.com";
        
        when(shortCodeGenerator.isValidShortCodeFormat(shortCode)).thenReturn(true);
        when(valueOperations.get("url:4c92")).thenReturn(longUrl);
        doThrow(new RuntimeException("Analytics error")).when(analyticsEventPublisher)
                .publishClickEvent(any());
        
        // Should still succeed
        String result = urlRedirectService.resolveShortCode(shortCode, httpRequest);
        assertEquals(longUrl, result);
    }

    @Test
    @DisplayName("Multiple redirects should increment Redis counter correctly")
    void resolveShortCodeMultipleRedirects() {
        String shortCode = "4c92";
        String longUrl = "https://example.com";
        
        when(shortCodeGenerator.isValidShortCodeFormat(shortCode)).thenReturn(true);
        when(valueOperations.get("url:4c92")).thenReturn(longUrl);
        when(valueOperations.increment("clicks:4c92")).thenReturn(1L, 2L, 3L);
        
        // Multiple redirects
        urlRedirectService.resolveShortCode(shortCode, httpRequest);
        urlRedirectService.resolveShortCode(shortCode, httpRequest);
        urlRedirectService.resolveShortCode(shortCode, httpRequest);
        
        verify(valueOperations, times(3)).increment("clicks:4c92");
    }

    @Test
    @DisplayName("No DB writes should occur on redirect path")
    void resolveShortCodeNoDbWrites() {
        String shortCode = "4c92";
        String longUrl = "https://example.com";
        
        when(shortCodeGenerator.isValidShortCodeFormat(shortCode)).thenReturn(true);
        when(valueOperations.get("url:4c92")).thenReturn(longUrl);
        
        urlRedirectService.resolveShortCode(shortCode, httpRequest);
        
        // Verify no save/update operations
        verify(urlMappingRepository, never()).save(any());
        verify(urlMappingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Short code not found should throw UrlNotFoundException")
    void resolveShortCodeNotFound() {
        String shortCode = "nonexistent";
        
        when(shortCodeGenerator.isValidShortCodeFormat(shortCode)).thenReturn(true);
        when(valueOperations.get("url:nonexistent")).thenReturn(null);
        when(urlMappingRepository.findValidByShortCode(eq(shortCode), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(urlMappingRepository.findByShortCode(shortCode))
                .thenReturn(Optional.empty());
        
        assertThrows(
                UrlRedirectService.UrlNotFoundException.class,
                () -> urlRedirectService.resolveShortCode(shortCode, httpRequest)
        );
    }
}




