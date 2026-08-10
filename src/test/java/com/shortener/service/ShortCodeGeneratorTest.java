package com.shortener.service;

import com.shortener.util.Base62Encoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ShortCodeGenerator.
 * 
 * Tests cover:
 * - Short code generation from database IDs
 * - Short code validation
 * - Decode functionality
 * - Edge cases (boundary values)
 * - Invalid input handling
 */
@DisplayName("ShortCodeGenerator Tests")
class ShortCodeGeneratorTest {

    private ShortCodeGenerator shortCodeGenerator;

    @BeforeEach
    void setUp() {
        shortCodeGenerator = new ShortCodeGenerator();
    }

    @Test
    @DisplayName("Generate short code from valid database ID should succeed")
    void generateShortCodeValidId() {
        long dbId = 1000000L;
        String shortCode = shortCodeGenerator.generateShortCode(dbId);
        
        assertNotNull(shortCode, "Short code should not be null");
        assertFalse(shortCode.isEmpty(), "Short code should not be empty");
        assertEquals("4c92", shortCode, "ID 1000000 should generate '4c92'");
    }

    @Test
    @DisplayName("Generate short code from ID 62 should return '10'")
    void generateShortCodeSixtyTwo() {
        String shortCode = shortCodeGenerator.generateShortCode(62L);
        assertEquals("10", shortCode);
    }

    @Test
    @DisplayName("Generate short code from ID 1 should return '1'")
    void generateShortCodeOne() {
        String shortCode = shortCodeGenerator.generateShortCode(1L);
        assertEquals("1", shortCode);
    }

    @Test
    @DisplayName("Generate short code from zero should throw IllegalArgumentException")
    void generateShortCodeZeroShouldThrow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> shortCodeGenerator.generateShortCode(0),
                "Generating short code from zero should throw exception"
        );
    }

    @Test
    @DisplayName("Generate short code from negative ID should throw IllegalArgumentException")
    void generateShortCodeNegativeShouldThrow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> shortCodeGenerator.generateShortCode(-1),
                "Generating short code from negative ID should throw exception"
        );
    }

    @Test
    @DisplayName("Generated short code should be within length limits")
    void generateShortCodeLength() {
        String shortCode = shortCodeGenerator.generateShortCode(1000000L);
        assertTrue(shortCode.length() >= 4, "Short code should be at least 4 characters");
        assertTrue(shortCode.length() <= 10, "Short code should be at most 10 characters");
    }

    @Test
    @DisplayName("Decode short code should return original database ID")
    void decodeShortCode() {
        long originalId = 1000000L;
        String shortCode = shortCodeGenerator.generateShortCode(originalId);
        long decodedId = shortCodeGenerator.decodeShortCode(shortCode);
        
        assertEquals(originalId, decodedId, 
                "Decoding short code should return original database ID");
    }

    @Test
    @DisplayName("Round-trip: generate then decode should return original ID")
    void roundTrip() {
        long[] testIds = {1L, 62L, 100L, 1000L, 1000000L, 1000000000L};
        
        for (long id : testIds) {
            String shortCode = shortCodeGenerator.generateShortCode(id);
            long decoded = shortCodeGenerator.decodeShortCode(shortCode);
            assertEquals(id, decoded, 
                    "Round-trip should work for ID: " + id);
        }
    }

    @Test
    @DisplayName("Decode null should throw IllegalArgumentException")
    void decodeNullShouldThrow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> shortCodeGenerator.decodeShortCode(null),
                "Decoding null should throw exception"
        );
    }

    @Test
    @DisplayName("Decode empty string should throw IllegalArgumentException")
    void decodeEmptyShouldThrow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> shortCodeGenerator.decodeShortCode(""),
                "Decoding empty string should throw exception"
        );
    }

    @Test
    @DisplayName("Decode invalid Base62 string should throw IllegalArgumentException")
    void decodeInvalidBase62ShouldThrow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> shortCodeGenerator.decodeShortCode("4c9@"),
                "Decoding invalid Base62 string should throw exception"
        );
    }

    @Test
    @DisplayName("isValidShortCodeFormat should return true for valid codes")
    void isValidShortCodeFormatValid() {
        assertTrue(shortCodeGenerator.isValidShortCodeFormat("4c92"));
        assertTrue(shortCodeGenerator.isValidShortCodeFormat("abc123"));
        assertTrue(shortCodeGenerator.isValidShortCodeFormat("ABCD1234"));
        assertTrue(shortCodeGenerator.isValidShortCodeFormat("1234567890")); // Max length
    }

    @Test
    @DisplayName("isValidShortCodeFormat should return false for invalid codes")
    void isValidShortCodeFormatInvalid() {
        // Too short
        assertFalse(shortCodeGenerator.isValidShortCodeFormat("123"));
        assertFalse(shortCodeGenerator.isValidShortCodeFormat("abc"));
        
        // Too long
        assertFalse(shortCodeGenerator.isValidShortCodeFormat("12345678901")); // 11 chars
        
        // Invalid characters
        assertFalse(shortCodeGenerator.isValidShortCodeFormat("4c9@"));
        assertFalse(shortCodeGenerator.isValidShortCodeFormat("hello-world"));
        assertFalse(shortCodeGenerator.isValidShortCodeFormat("test@123"));
        
        // Null or empty
        assertFalse(shortCodeGenerator.isValidShortCodeFormat(null));
        assertFalse(shortCodeGenerator.isValidShortCodeFormat(""));
    }

    @Test
    @DisplayName("Short code generation should handle large IDs")
    void generateShortCodeLargeId() {
        long largeId = 1000000000L;
        String shortCode = shortCodeGenerator.generateShortCode(largeId);
        
        assertNotNull(shortCode);
        assertTrue(shortCode.length() <= 10, 
                "Large ID should generate short code within length limit");
        
        // Verify round-trip
        long decoded = shortCodeGenerator.decodeShortCode(shortCode);
        assertEquals(largeId, decoded);
    }
}




