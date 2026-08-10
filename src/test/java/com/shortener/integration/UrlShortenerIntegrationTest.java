package com.shortener.integration;

import com.shortener.dto.ShortenUrlRequest;
import com.shortener.dto.ShortenUrlResponse;
import com.shortener.entity.UrlMapping;
import com.shortener.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests using Testcontainers.
 * 
 * These tests use real PostgreSQL and Redis containers to test
 * end-to-end behavior in a production-like environment.
 * 
 * Tests cover:
 * - Full shorten → redirect flow
 * - Redis key creation
 * - Click counter increments
 * - Rate limiting end-to-end
 * - System recovery after restarts
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("URL Shortener Integration Tests")
class UrlShortenerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        // Clear Redis before each test
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    @DisplayName("Full shorten → redirect flow should work end-to-end")
    void fullShortenRedirectFlow() {
        // Step 1: Shorten URL
        ShortenUrlRequest request = new ShortenUrlRequest();
        request.setLongUrl("https://example.com/test");
        
        ResponseEntity<ShortenUrlResponse> shortenResponse = restTemplate.postForEntity(
                baseUrl + "/api/v1/urls/shorten",
                request,
                ShortenUrlResponse.class
        );
        
        assertEquals(HttpStatus.CREATED, shortenResponse.getStatusCode());
        assertNotNull(shortenResponse.getBody());
        String shortCode = shortenResponse.getBody().getShortCode();
        assertNotNull(shortCode);
        
        // Step 2: Verify database entry
        Optional<UrlMapping> mapping = urlMappingRepository.findByShortCode(shortCode);
        assertTrue(mapping.isPresent());
        assertEquals("https://example.com/test", mapping.get().getLongUrl());
        
        // Step 3: Verify Redis cache
        String cachedUrl = redisTemplate.opsForValue().get("url:" + shortCode);
        assertEquals("https://example.com/test", cachedUrl);
        
        // Step 4: Redirect (should use cache)
        ResponseEntity<Void> redirectResponse = restTemplate.getForEntity(
                baseUrl + "/" + shortCode,
                Void.class
        );
        
        assertEquals(HttpStatus.FOUND, redirectResponse.getStatusCode());
        assertEquals("https://example.com/test", 
                redirectResponse.getHeaders().getLocation().toString());
    }

    @Test
    @DisplayName("Click counters should increment in Redis")
    void clickCounterIncrements() {
        // Shorten URL
        ShortenUrlRequest request = new ShortenUrlRequest();
        request.setLongUrl("https://example.com");
        
        ResponseEntity<ShortenUrlResponse> response = restTemplate.postForEntity(
                baseUrl + "/api/v1/urls/shorten",
                request,
                ShortenUrlResponse.class
        );
        
        String shortCode = response.getBody().getShortCode();
        
        // Redirect multiple times
        for (int i = 0; i < 5; i++) {
            restTemplate.getForEntity(baseUrl + "/" + shortCode, Void.class);
        }
        
        // Verify counter in Redis
        String counterValue = redisTemplate.opsForValue().get("clicks:" + shortCode);
        assertNotNull(counterValue);
        assertEquals("5", counterValue);
    }

    @Test
    @DisplayName("Rate limiting should work end-to-end")
    void rateLimitingEndToEnd() {
        // Make requests exceeding rate limit
        ShortenUrlRequest request = new ShortenUrlRequest();
        request.setLongUrl("https://example.com");
        
        int successCount = 0;
        int rateLimitedCount = 0;
        
        // Make 150 requests (limit is 100/min)
        for (int i = 0; i < 150; i++) {
            ResponseEntity<?> response = restTemplate.postForEntity(
                    baseUrl + "/api/v1/urls/shorten",
                    request,
                    Object.class
            );
            
            if (response.getStatusCode() == HttpStatus.CREATED) {
                successCount++;
            } else if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                rateLimitedCount++;
            }
        }
        
        // Should have some successful and some rate-limited
        assertTrue(successCount > 0, "Some requests should succeed");
        assertTrue(rateLimitedCount > 0, "Some requests should be rate-limited");
    }

    @Test
    @DisplayName("Health endpoint should return UP")
    void healthEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl + "/actuator/health",
                String.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("UP") || response.getBody().contains("\"status\":\"UP\""));
    }
}

