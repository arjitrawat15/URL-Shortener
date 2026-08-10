package com.shortener.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Base62Encoder.
 * 
 * Tests cover:
 * - Basic encoding/decoding
 * - Edge cases (0, 1, boundary values)
 * - Round-trip correctness
 * - Invalid input handling
 * - Overflow protection
 */
@DisplayName("Base62Encoder Tests")
class Base62EncoderTest {

    @Test
    @DisplayName("Encode zero should return '0'")
    void encodeZero() {
        String result = Base62Encoder.encode(0);
        assertEquals("0", result, "Zero should encode to '0'");
    }

    @Test
    @DisplayName("Encode one should return '1'")
    void encodeOne() {
        String result = Base62Encoder.encode(1);
        assertEquals("1", result, "One should encode to '1'");
    }

    @Test
    @DisplayName("Encode 61 should return 'Z' (last single character)")
    void encodeSixtyOne() {
        String result = Base62Encoder.encode(61);
        assertEquals("Z", result, "61 should encode to 'Z'");
    }

    @Test
    @DisplayName("Encode 62 should return '10' (first two-character code)")
    void encodeSixtyTwo() {
        String result = Base62Encoder.encode(62);
        assertEquals("10", result, "62 should encode to '10'");
    }

    @Test
    @DisplayName("Encode Long.MAX_VALUE should succeed")
    void encodeLongMaxValue() {
        long maxValue = Long.MAX_VALUE;
        String result = Base62Encoder.encode(maxValue);
        assertNotNull(result, "Long.MAX_VALUE should encode successfully");
        assertFalse(result.isEmpty(), "Encoded result should not be empty");
        // Long.MAX_VALUE = 9,223,372,036,854,775,807 encodes to "AzL8n0Y58m7"
        assertEquals("AzL8n0Y58m7", result);
    }

    @Test
    @DisplayName("Encode negative number should throw IllegalArgumentException")
    void encodeNegativeShouldThrow() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Base62Encoder.encode(-1),
                "Encoding negative number should throw IllegalArgumentException"
        );
        assertTrue(exception.getMessage().contains("non-negative"));
    }

    @Test
    @DisplayName("Decode '0' should return 0")
    void decodeZero() {
        long result = Base62Encoder.decode("0");
        assertEquals(0, result, "Decoding '0' should return 0");
    }

    @Test
    @DisplayName("Decode '1' should return 1")
    void decodeOne() {
        long result = Base62Encoder.decode("1");
        assertEquals(1, result, "Decoding '1' should return 1");
    }

    @Test
    @DisplayName("Decode 'Z' should return 61")
    void decodeZ() {
        long result = Base62Encoder.decode("Z");
        assertEquals(61, result, "Decoding 'Z' should return 61");
    }

    @Test
    @DisplayName("Decode '10' should return 62")
    void decodeTen() {
        long result = Base62Encoder.decode("10");
        assertEquals(62, result, "Decoding '10' should return 62");
    }

    @Test
    @DisplayName("Decode '4c92' should return 1000000")
    void decodeExample() {
        long result = Base62Encoder.decode("4c92");
        assertEquals(1000000, result, "Decoding '4c92' should return 1000000");
    }

    @Test
    @DisplayName("Decode 'AzL8n0Y58m7' should return Long.MAX_VALUE")
    void decodeLongMaxValue() {
        long result = Base62Encoder.decode("AzL8n0Y58m7");
        assertEquals(Long.MAX_VALUE, result, "Should decode to Long.MAX_VALUE");
    }

    @Test
    @DisplayName("Decode null should throw IllegalArgumentException")
    void decodeNullShouldThrow() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Base62Encoder.decode(null),
                "Decoding null should throw IllegalArgumentException"
        );
        assertTrue(exception.getMessage().contains("null") || 
                   exception.getMessage().contains("empty"));
    }

    @Test
    @DisplayName("Decode empty string should throw IllegalArgumentException")
    void decodeEmptyShouldThrow() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Base62Encoder.decode(""),
                "Decoding empty string should throw IllegalArgumentException"
        );
        assertTrue(exception.getMessage().contains("empty"));
    }

    @Test
    @DisplayName("Decode invalid character should throw IllegalArgumentException")
    void decodeInvalidCharacterShouldThrow() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Base62Encoder.decode("4c9@"),
                "Decoding invalid character should throw IllegalArgumentException"
        );
        assertTrue(exception.getMessage().contains("Invalid character"));
    }

    @Test
    @DisplayName("Decode string causing overflow should throw IllegalArgumentException")
    void decodeOverflowShouldThrow() {
        // Create a string that would cause overflow
        // This is tricky - we'd need a string longer than Long.MAX_VALUE encoding
        // For now, test with a very long valid string
        String veryLongString = "AzL8n0Y58m8"; // One more than Long.MAX_VALUE encoding
        assertThrows(
                IllegalArgumentException.class,
                () -> Base62Encoder.decode(veryLongString),
                "Decoding string causing overflow should throw IllegalArgumentException"
        );
    }

    @ParameterizedTest
    @DisplayName("Round-trip: encode then decode should return original value")
    @CsvSource({
            "0",
            "1",
            "10",
            "61",
            "62",
            "100",
            "1000",
            "10000",
            "100000",
            "1000000",
            "1000000000"
    })
    void roundTrip(long value) {
        String encoded = Base62Encoder.encode(value);
        long decoded = Base62Encoder.decode(encoded);
        assertEquals(value, decoded, 
                "Round-trip should return original value for: " + value);
    }

    @Test
    @DisplayName("Round-trip with Long.MAX_VALUE should work")
    void roundTripLongMaxValue() {
        long original = Long.MAX_VALUE;
        String encoded = Base62Encoder.encode(original);
        long decoded = Base62Encoder.decode(encoded);
        assertEquals(original, decoded, "Round-trip should work for Long.MAX_VALUE");
    }

    @Test
    @DisplayName("isValidBase62 should return true for valid strings")
    void isValidBase62Valid() {
        assertTrue(Base62Encoder.isValidBase62("0"));
        assertTrue(Base62Encoder.isValidBase62("1"));
        assertTrue(Base62Encoder.isValidBase62("Z"));
        assertTrue(Base62Encoder.isValidBase62("10"));
        assertTrue(Base62Encoder.isValidBase62("4c92"));
        assertTrue(Base62Encoder.isValidBase62("AzL8n0Y58m7"));
    }

    @Test
    @DisplayName("isValidBase62 should return false for invalid strings")
    void isValidBase62Invalid() {
        assertFalse(Base62Encoder.isValidBase62(null));
        assertFalse(Base62Encoder.isValidBase62(""));
        assertFalse(Base62Encoder.isValidBase62("4c9@"));
        assertFalse(Base62Encoder.isValidBase62("hello-world"));
        assertFalse(Base62Encoder.isValidBase62("test@123"));
    }

    @Test
    @DisplayName("getEncodedLength should return correct length")
    void getEncodedLength() {
        assertEquals(1, Base62Encoder.getEncodedLength(0));
        assertEquals(1, Base62Encoder.getEncodedLength(1));
        assertEquals(1, Base62Encoder.getEncodedLength(61));
        assertEquals(2, Base62Encoder.getEncodedLength(62));
        assertEquals(2, Base62Encoder.getEncodedLength(3843)); // 62^2 - 1
        assertEquals(3, Base62Encoder.getEncodedLength(3844)); // 62^2
        assertEquals(4, Base62Encoder.getEncodedLength(1000000));
    }
}




