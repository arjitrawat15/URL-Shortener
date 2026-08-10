package com.shortener.service;

import com.shortener.util.Base62Encoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Service for generating short codes from database IDs.
 * 
 * This implementation uses database auto-increment IDs as the source of uniqueness.
 * The strategy is:
 * 1. Insert URL into database (gets auto-generated ID)
 * 2. Convert ID to Base62 to create short code
 * 3. Update the record with the short code
 * 
 * Advantages:
 * - Simple and reliable (no collisions with auto-increment)
 * - No coordination needed between multiple servers
 * - Sequential IDs (though this reveals total URL count)
 * 
 * Disadvantages:
 * - Database becomes bottleneck at very high scale
 * - Sequential IDs are predictable (privacy concern)
 * - Requires two database operations (insert, then update)
 * 
 * Alternative Strategy (Snowflake ID):
 * 
 * For production at scale, consider switching to distributed ID generation:
 * 
 * 1. Use Twitter Snowflake or similar distributed ID generator
 *    - Generates 64-bit IDs across multiple servers
 *    - Includes timestamp, machine ID, and sequence number
 *    - No single point of failure
 * 
 * 2. Implementation would look like:
 *    <pre>
 *    public String generateShortCode() {
 *        long id = snowflakeIdGenerator.nextId();
 *        return Base62Encoder.encode(id);
 *    }
 *    </pre>
 * 
 * 3. Benefits of Snowflake:
 *    - Non-sequential (better privacy)
 *    - Scalable across multiple servers
 *    - Includes timestamp (useful for analytics)
 *    - No database round-trip needed
 * 
 * 4. To swap implementations:
 *    - Create a ShortCodeGenerator interface
 *    - Implement DatabaseAutoIncrementShortCodeGenerator (current)
 *    - Implement SnowflakeShortCodeGenerator (alternative)
 *    - Use @Primary or @Qualifier to select implementation
 *    - Or use configuration property to switch at runtime
 * 
 * Current Implementation Flow:
 * 
 * 1. Service layer calls generateShortCode(id)
 * 2. This method converts the database ID to Base62
 * 3. Returns the short code (e.g., "4c92")
 * 4. Repository updates the UrlMapping with the short code
 * 
 * Note: The actual database ID is obtained from the entity after insertion.
 * This class only handles the conversion from ID to short code.
 */
@Component
public class ShortCodeGenerator {

    private static final Logger logger = LoggerFactory.getLogger(ShortCodeGenerator.class);

    /**
     * Minimum length for short codes (4 characters).
     * This ensures codes are reasonably short while providing enough combinations.
     * 4 characters = 62^4 = 14,776,336 possible codes
     */
    private static final int MIN_SHORT_CODE_LENGTH = 4;

    /**
     * Maximum length for short codes (10 characters).
     * This provides 62^10 = 839,299,365,868,340,224 possible codes.
     * Long.MAX_VALUE encodes to 11 characters, so 10 is a reasonable limit.
     */
    private static final int MAX_SHORT_CODE_LENGTH = 10;

    /**
     * Generates a short code from a database auto-increment ID.
     * 
     * This is the primary method for creating short codes.
     * It converts the database-generated ID to a Base62-encoded string.
     * 
     * @param databaseId the auto-generated database ID (must be > 0)
     * @return Base62-encoded short code (e.g., "4c92" for ID 1,000,000)
     * @throws IllegalArgumentException if databaseId is invalid
     * 
     * Example:
     * <pre>
     * // After inserting URL, entity.getId() returns 1000000
     * String shortCode = generator.generateShortCode(1000000L);
     * // Returns "4c92"
     * </pre>
     */
    public String generateShortCode(long databaseId) {
        if (databaseId <= 0) {
            throw new IllegalArgumentException(
                "Database ID must be positive, got: " + databaseId
            );
        }

        String shortCode = Base62Encoder.encode(databaseId);

        // Pad short code to minimum length if needed
        // Small IDs (1-238327) encode to less than 4 characters
        // We pad with leading '0' characters to ensure minimum length
        if (shortCode.length() < MIN_SHORT_CODE_LENGTH) {
            int paddingNeeded = MIN_SHORT_CODE_LENGTH - shortCode.length();
            shortCode = "0".repeat(paddingNeeded) + shortCode;
            logger.debug("Padded short code to minimum length: '{}' for ID {}", shortCode, databaseId);
        }

        if (shortCode.length() > MAX_SHORT_CODE_LENGTH) {
            logger.error("Generated short code '{}' exceeds maximum length {} for ID {}",
                    shortCode, MAX_SHORT_CODE_LENGTH, databaseId);
            throw new IllegalStateException(
                String.format("Short code '%s' exceeds maximum length %d", 
                    shortCode, MAX_SHORT_CODE_LENGTH)
            );
        }

        logger.debug("Generated short code '{}' from database ID {}", shortCode, databaseId);
        return shortCode;
    }

    /**
     * Decodes a short code back to its original database ID.
     * 
     * This is useful for:
     * - Validating short codes
     * - Debugging and analytics
     * - Reverse lookups (though repository.findByShortCode() is preferred)
     * 
     * @param shortCode the Base62-encoded short code
     * @return the original database ID
     * @throws IllegalArgumentException if shortCode is invalid
     * 
     * Example:
     * <pre>
     * long id = generator.decodeShortCode("4c92");
     * // Returns 1000000
     * </pre>
     */
    public long decodeShortCode(String shortCode) {
        if (shortCode == null || shortCode.isEmpty()) {
            throw new IllegalArgumentException("Short code cannot be null or empty");
        }

        if (!Base62Encoder.isValidBase62(shortCode)) {
            throw new IllegalArgumentException(
                "Invalid Base62 short code: " + shortCode
            );
        }

        try {
            return Base62Encoder.decode(shortCode);
        } catch (IllegalArgumentException e) {
            logger.error("Failed to decode short code '{}': {}", shortCode, e.getMessage());
            throw new IllegalArgumentException(
                "Invalid short code format: " + shortCode, e
            );
        }
    }

    /**
     * Validates if a string is a valid short code format.
     * 
     * Checks:
     * - Not null or empty
     * - Contains only Base62 characters
     * - Length is within acceptable range (4-10 characters)
     * 
     * @param shortCode the string to validate
     * @return true if the string is a valid short code format
     */
    public boolean isValidShortCodeFormat(String shortCode) {
        if (shortCode == null || shortCode.isEmpty()) {
            return false;
        }

        if (shortCode.length() < MIN_SHORT_CODE_LENGTH || 
            shortCode.length() > MAX_SHORT_CODE_LENGTH) {
            return false;
        }

        return Base62Encoder.isValidBase62(shortCode);
    }

    // Unit-test-like examples (for documentation and manual verification)
    /*
     * Test cases for generateShortCode():
     * 
     * generateShortCode(1) → "1" (but should be padded or handled specially)
     * generateShortCode(62) → "10"
     * generateShortCode(1000000) → "4c92"
     * generateShortCode(1000000000) → "15ftGg"
     * generateShortCode(0) → IllegalArgumentException
     * generateShortCode(-1) → IllegalArgumentException
     * 
     * Test cases for decodeShortCode():
     * 
     * decodeShortCode("4c92") → 1000000
     * decodeShortCode("15ftGg") → 1000000000
     * decodeShortCode(null) → IllegalArgumentException
     * decodeShortCode("") → IllegalArgumentException
     * decodeShortCode("4c9@") → IllegalArgumentException
     * 
     * Round-trip tests:
     * 
     * decodeShortCode(generateShortCode(1000000)) → 1000000
     * decodeShortCode(generateShortCode(1000000000)) → 1000000000
     * 
     * Integration with database flow:
     * 
     * 1. Insert UrlMapping entity (id = null)
     * 2. After save, entity.getId() = 1000000
     * 3. String shortCode = generateShortCode(1000000) → "4c92"
     * 4. entity.setShortCode(shortCode)
     * 5. save(entity) again to persist short code
     * 
     * Alternative flow (if using Snowflake):
     * 
     * 1. long id = snowflakeGenerator.nextId() → 1234567890123456789
     * 2. String shortCode = Base62Encoder.encode(id) → "8n0Y58m7K"
     * 3. Create UrlMapping with shortCode directly
     * 4. No need for two-step insert/update
     */
}

