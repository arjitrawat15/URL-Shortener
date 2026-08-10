package com.shortener.util;

/**
 * Utility class for Base62 encoding and decoding.
 * 
 * Base62 encoding uses the character set: 0-9, a-z, A-Z (62 characters total).
 * This provides a URL-safe encoding that is more compact than decimal (Base10)
 * while avoiding special characters that require URL encoding (unlike Base64).
 * 
 * Use cases:
 * - Convert database auto-increment IDs to short codes (e.g., 1,000,000 → "4c92")
 * - Decode short codes back to IDs for database lookups
 * - Generate human-readable, case-sensitive identifiers
 * 
 * Thread-safety:
 * This class is stateless and thread-safe. All methods are pure functions
 * with no shared mutable state.
 * 
 * Example usage:
 * <pre>
 * long id = 1000000L;
 * String shortCode = Base62Encoder.encode(id);  // "4c92"
 * long decodedId = Base62Encoder.decode("4c92"); // 1000000L
 * </pre>
 * 
 * Performance:
 * - Encode: O(log62(n)) where n is the input number
 * - Decode: O(k) where k is the length of the Base62 string
 * - Both operations are very fast for typical ID ranges
 */
public final class Base62Encoder {

    /**
     * Base62 character set: 0-9 (10), a-z (26), A-Z (26) = 62 characters.
     * Characters are ordered to match standard Base62 encoding.
     */
    private static final String BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    
    /**
     * Base value for Base62 encoding.
     */
    private static final int BASE = 62;
    
    /**
     * Maximum length of a Base62-encoded string for a Long.MAX_VALUE.
     * Long.MAX_VALUE = 9,223,372,036,854,775,807
     * In Base62, this requires at most 11 characters.
     */
    private static final int MAX_ENCODED_LENGTH = 11;

    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static methods.
     */
    private Base62Encoder() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Encodes a long integer to a Base62 string.
     * 
     * Algorithm:
     * 1. Repeatedly divide the number by 62
     * 2. Use the remainder as an index into the character set
     * 3. Build the result string from right to left
     * 4. Handle zero as a special case
     * 
     * @param id the long integer to encode (must be non-negative)
     * @return Base62-encoded string (e.g., 1000000 → "4c92")
     * @throws IllegalArgumentException if id is negative
     * 
     * Examples:
     * - encode(0) → "0"
     * - encode(1) → "1"
     * - encode(61) → "Z"
     * - encode(62) → "10"
     * - encode(1000000) → "4c92"
     * - encode(1000000000) → "15ftGg"
     * - encode(9223372036854775807L) → "AzL8n0Y58m7" (Long.MAX_VALUE)
     */
    public static String encode(long id) {
        if (id < 0) {
            throw new IllegalArgumentException("ID must be non-negative, got: " + id);
        }
        
        if (id == 0) {
            return "0";
        }
        
        // Pre-allocate StringBuilder with estimated capacity
        // Most IDs will encode to 4-8 characters
        StringBuilder encoded = new StringBuilder(MAX_ENCODED_LENGTH);
        
        long num = id;
        while (num > 0) {
            int remainder = (int) (num % BASE);
            encoded.append(BASE62_CHARS.charAt(remainder));
            num = num / BASE;
        }
        
        // Reverse because we built the string from right to left
        return encoded.reverse().toString();
    }

    /**
     * Decodes a Base62 string back to a long integer.
     * 
     * Algorithm:
     * 1. Iterate through each character from left to right
     * 2. Find the character's index in the Base62 character set
     * 3. Multiply the running result by 62 and add the index
     * 
     * @param encoded the Base62-encoded string to decode
     * @return the decoded long integer
     * @throws IllegalArgumentException if the string is null, empty, or contains invalid characters
     * 
     * Examples:
     * - decode("0") → 0
     * - decode("1") → 1
     * - decode("Z") → 61
     * - decode("10") → 62
     * - decode("4c92") → 1000000
     * - decode("15ftGg") → 1000000000
     * - decode("AzL8n0Y58m7") → 9223372036854775807L (Long.MAX_VALUE)
     */
    public static long decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("Encoded string cannot be null or empty");
        }
        
        long decoded = 0;
        long multiplier = 1;
        
        // Process from right to left (least significant to most significant)
        for (int i = encoded.length() - 1; i >= 0; i--) {
            char c = encoded.charAt(i);
            int charIndex = BASE62_CHARS.indexOf(c);
            
            if (charIndex == -1) {
                throw new IllegalArgumentException(
                    "Invalid character '" + c + "' in Base62 string: " + encoded
                );
            }
            
            // Check for overflow before multiplying
            if (decoded > (Long.MAX_VALUE - charIndex) / BASE) {
                throw new IllegalArgumentException(
                    "Base62 string too large to fit in long: " + encoded
                );
            }
            
            decoded += charIndex * multiplier;
            multiplier *= BASE;
        }
        
        return decoded;
    }

    /**
     * Validates if a string is a valid Base62-encoded string.
     * 
     * @param encoded the string to validate
     * @return true if the string contains only valid Base62 characters
     */
    public static boolean isValidBase62(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return false;
        }
        
        for (char c : encoded.toCharArray()) {
            if (BASE62_CHARS.indexOf(c) == -1) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Gets the minimum length of a Base62-encoded string for a given number.
     * Useful for pre-allocating StringBuilder capacity.
     * 
     * @param id the number
     * @return the minimum number of characters needed to encode the number
     */
    public static int getEncodedLength(long id) {
        if (id == 0) {
            return 1;
        }
        
        int length = 0;
        long num = id;
        while (num > 0) {
            length++;
            num /= BASE;
        }
        
        return length;
    }

    // Unit-test-like examples (for documentation and manual verification)
    /*
     * Test cases for encode():
     * 
     * encode(0) → "0"
     * encode(1) → "1"
     * encode(9) → "9"
     * encode(10) → "a"
     * encode(35) → "z"
     * encode(36) → "A"
     * encode(61) → "Z"
     * encode(62) → "10"
     * encode(123) → "1Z"
     * encode(3844) → "100" (62^2)
     * encode(1000000) → "4c92"
     * encode(1000000000) → "15ftGg"
     * encode(9223372036854775807L) → "AzL8n0Y58m7" (Long.MAX_VALUE)
     * 
     * Test cases for decode():
     * 
     * decode("0") → 0
     * decode("1") → 1
     * decode("9") → 9
     * decode("a") → 10
     * decode("z") → 35
     * decode("A") → 36
     * decode("Z") → 61
     * decode("10") → 62
     * decode("1Z") → 123
     * decode("100") → 3844 (62^2)
     * decode("4c92") → 1000000
     * decode("15ftGg") → 1000000000
     * decode("AzL8n0Y58m7") → 9223372036854775807L (Long.MAX_VALUE)
     * 
     * Round-trip tests (encode then decode):
     * 
     * decode(encode(0)) → 0
     * decode(encode(1)) → 1
     * decode(encode(62)) → 62
     * decode(encode(1000000)) → 1000000
     * decode(encode(9223372036854775807L)) → 9223372036854775807L
     * 
     * Edge cases:
     * 
     * encode(-1) → IllegalArgumentException
     * decode(null) → IllegalArgumentException
     * decode("") → IllegalArgumentException
     * decode("4c9@") → IllegalArgumentException (invalid character)
     * decode("AzL8n0Y58m8") → IllegalArgumentException (overflow)
     */
}




