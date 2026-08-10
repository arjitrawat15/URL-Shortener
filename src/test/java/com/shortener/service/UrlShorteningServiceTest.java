package com.shortener.service;

import com.shortener.dto.ShortenUrlRequest;
import com.shortener.dto.ShortenUrlResponse;
import com.shortener.entity.UrlMapping;
import com.shortener.repository.UrlMappingRepository;
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
 * Unit tests for UrlShorteningService.
 * 
 * Tests cover:
 * - Successful URL shortening
 * - URL normalization
 * - Custom alias handling
 * - Error scenarios
 * - Failure handling
 * - Transaction behavior
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UrlShorteningService Tests")
class UrlShorteningServiceTest {

    @Mock
    private UrlMappingRepository urlMappingRepository;

    @Mock
    private ShortCodeGenerator shortCodeGenerator;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private UrlShorteningService urlShorteningService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("Successful URL shortening should return response with short code")
    void shortenUrlSuccess() {
        // Arrange
        ShortenUrlRequest request = new ShortenUrlRequest();
        request.setLongUrl("https://example.com");
        
        UrlMapping savedMapping = new UrlMapping();
        savedMapping.setId(1000000L);
        savedMapping.setLongUrl("https://example.com");
        
        when(urlMappingRepository.save(any(UrlMapping.class))).thenReturn(savedMapping);
        when(shortCodeGenerator.generateShortCode(1000000L)).thenReturn("4c92");
        
        // Act
        ShortenUrlResponse response = urlShorteningService.shortenUrl(request, null);
        
        // Assert
        assertNotNull(response);
        assertEquals("4c92", response.getShortCode());
        assertEquals("https://example.com", response.getLongUrl());
        verify(urlMappingRepository, times(2)).save(any(UrlMapping.class)); // Insert + update
        verify(shortCodeGenerator).generateShortCode(1000000L);
    }

    @Test
    @DisplayName("URL normalization should add https:// if missing")
    void normalizeUrlAddsHttps() {
        ShortenUrlRequest request = new ShortenUrlRequest();
        request.setLongUrl("example.com");
        
        UrlMapping savedMapping = new UrlMapping();
        savedMapping.setId(1L);
        when(urlMappingRepository.save(any(UrlMapping.class))).thenReturn(savedMapping);
        when(shortCodeGenerator.generateShortCode(1L)).thenReturn("1");
        
        ShortenUrlResponse response = urlShorteningService.shortenUrl(request, null);
        
        verify(urlMappingRepository).save(argThat(mapping -> 
                mapping.getLongUrl().equals("https://example.com")));
    }

    @Test
    @DisplayName("Custom alias should be used when provided and available")
    void shortenUrlWithCustomAlias() {
        ShortenUrlRequest request = new ShortenUrlRequest();
        request.setLongUrl("https://example.com");
        request.setCustomAlias("my-link");
        
        when(urlMappingRepository.existsByShortCode("my-link")).thenReturn(false);
        when(urlMappingRepository.existsByCustomAlias("my-link")).thenReturn(false);
        
        UrlMapping savedMapping = new UrlMapping();
        savedMapping.setId(1L);
        when(urlMappingRepository.save(any(UrlMapping.class))).thenReturn(savedMapping);
        
        ShortenUrlResponse response = urlShorteningService.shortenUrl(request, null);
        
        assertEquals("my-link", response.getShortCode());
        verify(shortCodeGenerator, never()).generateShortCode(anyLong());
    }

    @Test
    @DisplayName("Custom alias conflict should throw IllegalArgumentException")
    void shortenUrlCustomAliasConflict() {
        ShortenUrlRequest request = new ShortenUrlRequest();
        request.setLongUrl("https://example.com");
        request.setCustomAlias("existing");
        
        when(urlMappingRepository.existsByShortCode("existing")).thenReturn(true);
        
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> urlShorteningService.shortenUrl(request, null)
        );
        
        assertTrue(exception.getMessage().contains("already taken"));
        verify(urlMappingRepository, never()).save(any());
    }

    @Test
    @DisplayName("Invalid URL format should throw IllegalArgumentException")
    void shortenUrlInvalidUrl() {
        ShortenUrlRequest request = new ShortenUrlRequest();
        request.setLongUrl("not-a-valid-url");
        
        assertThrows(
                IllegalArgumentException.class,
                () -> urlShorteningService.shortenUrl(request, null)
        );
    }

    @Test
    @DisplayName("Expiration date should be set when provided")
    void shortenUrlWithExpiration() {
        ShortenUrlRequest request = new ShortenUrlRequest();
        request.setLongUrl("https://example.com");
        request.setExpirationDays(30);
        
        UrlMapping savedMapping = new UrlMapping();
        savedMapping.setId(1L);
        when(urlMappingRepository.save(any(UrlMapping.class))).thenReturn(savedMapping);
        when(shortCodeGenerator.generateShortCode(1L)).thenReturn("1");
        
        urlShorteningService.shortenUrl(request, null);
        
        verify(urlMappingRepository).save(argThat(mapping -> 
                mapping.getExpiresAt() != null &&
                mapping.getExpiresAt().isAfter(LocalDateTime.now())));
    }

    @Test
    @DisplayName("Tags should be set when provided")
    void shortenUrlWithTags() {
        ShortenUrlRequest request = new ShortenUrlRequest();
        request.setLongUrl("https://example.com");
        request.setTags(java.util.Arrays.asList("tag1", "tag2"));
        
        UrlMapping savedMapping = new UrlMapping();
        savedMapping.setId(1L);
        when(urlMappingRepository.save(any(UrlMapping.class))).thenReturn(savedMapping);
        when(shortCodeGenerator.generateShortCode(1L)).thenReturn("1");
        
        urlShorteningService.shortenUrl(request, null);
        
        verify(urlMappingRepository).save(argThat(mapping -> 
                mapping.getTags() != null &&
                mapping.getTags().contains("tag1") &&
                mapping.getTags().contains("tag2")));
    }

    @Test
    @DisplayName("Redis cache write failure should not fail the operation")
    void shortenUrlRedisFailure() {
        ShortenUrlRequest request = new ShortenUrlRequest();
        request.setLongUrl("https://example.com");
        
        UrlMapping savedMapping = new UrlMapping();
        savedMapping.setId(1L);
        savedMapping.setShortCode("1");
        when(urlMappingRepository.save(any(UrlMapping.class))).thenReturn(savedMapping);
        when(shortCodeGenerator.generateShortCode(1L)).thenReturn("1");
        
        // Simulate Redis failure
        doThrow(new RuntimeException("Redis error")).when(valueOperations)
                .set(anyString(), anyString(), anyLong(), any());
        
        // Should still succeed
        ShortenUrlResponse response = urlShorteningService.shortenUrl(request, null);
        assertNotNull(response);
    }

    @Test
    @DisplayName("Database failure should propagate exception")
    void shortenUrlDatabaseFailure() {
        ShortenUrlRequest request = new ShortenUrlRequest();
        request.setLongUrl("https://example.com");
        
        when(urlMappingRepository.save(any(UrlMapping.class)))
                .thenThrow(new RuntimeException("Database error"));
        
        assertThrows(
                RuntimeException.class,
                () -> urlShorteningService.shortenUrl(request, null)
        );
    }
}




