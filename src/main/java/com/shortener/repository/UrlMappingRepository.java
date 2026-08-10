package com.shortener.repository;

import com.shortener.entity.UrlMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for UrlMapping entity.
 * 
 * Provides CRUD operations and custom query methods for URL mappings.
 * 
 * Design decisions:
 * - Extends JpaRepository for standard CRUD operations
 * - Custom query methods for common use cases (lookup by short code, user URLs, etc.)
 * - Uses @Query for complex queries and bulk operations
 * - Returns Optional for single-result queries to handle null cases gracefully
 * - Pageable support for paginated queries (essential for user URL listings)
 */
@Repository
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    /**
     * Finds a URL mapping by its short code.
     * This is the most critical query for redirect operations.
     * 
     * @param shortCode the short code to search for
     * @return Optional containing the UrlMapping if found
     */
    Optional<UrlMapping> findByShortCode(String shortCode);

    /**
     * Finds a URL mapping by custom alias.
     * Used when users provide custom aliases instead of auto-generated codes.
     * 
     * @param customAlias the custom alias to search for
     * @return Optional containing the UrlMapping if found
     */
    Optional<UrlMapping> findByCustomAlias(String customAlias);

    /**
     * Checks if a short code already exists.
     * Used for collision detection during URL creation.
     * 
     * @param shortCode the short code to check
     * @return true if the short code exists
     */
    boolean existsByShortCode(String shortCode);

    /**
     * Checks if a custom alias already exists.
     * Used for validation before creating URLs with custom aliases.
     * 
     * @param customAlias the custom alias to check
     * @return true if the custom alias exists
     */
    boolean existsByCustomAlias(String customAlias);

    /**
     * Finds all URL mappings created by a specific user.
     * Returns paginated results for efficient handling of large datasets.
     * 
     * @param userId the user ID
     * @param pageable pagination parameters (page, size, sort)
     * @return Page of UrlMapping entities
     */
    Page<UrlMapping> findByUserId(Long userId, Pageable pageable);

    /**
     * Finds all active URL mappings created by a specific user.
     * Filters out inactive/deleted URLs.
     * 
     * @param userId the user ID
     * @param isActive whether the URL is active
     * @param pageable pagination parameters
     * @return Page of active UrlMapping entities
     */
    Page<UrlMapping> findByUserIdAndIsActive(Long userId, Boolean isActive, Pageable pageable);

    /**
     * Finds all expired URL mappings.
     * Used by cleanup jobs to identify URLs that need to be deactivated or deleted.
     * 
     * @param currentTime the current timestamp for comparison
     * @return List of expired URL mappings
     */
    @Query("SELECT u FROM UrlMapping u WHERE u.expiresAt IS NOT NULL AND u.expiresAt < :currentTime")
    List<UrlMapping> findExpiredUrls(@Param("currentTime") LocalDateTime currentTime);

    /**
     * Finds URL mappings that contain any of the specified tags.
     * Uses PostgreSQL array containment operator (@>).
     * 
     * Note: This query works with PostgreSQL. For MySQL, you may need to use
     * JSON functions or a different approach.
     * 
     * @param tags list of tags to search for
     * @param pageable pagination parameters
     * @return Page of UrlMapping entities matching the tags
     */
    @Query(value = "SELECT * FROM url_mappings WHERE tags && CAST(:tags AS text[])",
           nativeQuery = true)
    Page<UrlMapping> findByTagsContaining(@Param("tags") List<String> tags, Pageable pageable);

    /**
     * Finds URL mappings by user ID and tags.
     * Combines user filtering with tag filtering.
     * 
     * @param userId the user ID
     * @param tags list of tags to search for
     * @param pageable pagination parameters
     * @return Page of UrlMapping entities
     */
    @Query(value = "SELECT * FROM url_mappings WHERE user_id = :userId AND tags && CAST(:tags AS text[])",
           nativeQuery = true)
    Page<UrlMapping> findByUserIdAndTagsContaining(@Param("userId") Long userId,
                                                    @Param("tags") List<String> tags,
                                                    Pageable pageable);

    /**
     * Atomically increments the click count for a URL mapping.
     * Uses database-level atomic operation to avoid race conditions.
     * 
     * This is more efficient than fetching, incrementing, and saving,
     * especially under high concurrency.
     * 
     * @param shortCode the short code of the URL
     * @return number of rows affected (should be 1 if successful)
     */
    @Modifying
    @Query("UPDATE UrlMapping u SET u.clickCount = u.clickCount + 1 WHERE u.shortCode = :shortCode")
    int incrementClickCount(@Param("shortCode") String shortCode);

    /**
     * Finds URL mappings created within a date range.
     * Useful for analytics and reporting.
     * 
     * @param startDate start of the date range (inclusive)
     * @param endDate end of the date range (inclusive)
     * @param pageable pagination parameters
     * @return Page of UrlMapping entities
     */
    Page<UrlMapping> findByCreatedAtBetween(LocalDateTime startDate,
                                            LocalDateTime endDate,
                                            Pageable pageable);

    /**
     * Finds URL mappings by user ID within a date range.
     * Combines user filtering with date range filtering.
     * 
     * @param userId the user ID
     * @param startDate start of the date range (inclusive)
     * @param endDate end of the date range (inclusive)
     * @param pageable pagination parameters
     * @return Page of UrlMapping entities
     */
    Page<UrlMapping> findByUserIdAndCreatedAtBetween(Long userId,
                                                     LocalDateTime startDate,
                                                     LocalDateTime endDate,
                                                     Pageable pageable);

    /**
     * Counts the number of active URLs created by a user.
     * Used for quota enforcement and statistics.
     * 
     * @param userId the user ID
     * @return count of active URLs
     */
    long countByUserIdAndIsActive(Long userId, Boolean isActive);

    /**
     * Finds a URL mapping by short code, ensuring it's valid (active and not expired).
     * Used in redirect flow to quickly check if a URL can be used.
     * 
     * @param shortCode the short code
     * @param currentTime the current timestamp for expiration check
     * @return Optional containing the UrlMapping if found and valid
     */
    @Query("SELECT u FROM UrlMapping u WHERE u.shortCode = :shortCode " +
           "AND u.isActive = true " +
           "AND (u.expiresAt IS NULL OR u.expiresAt > :currentTime)")
    Optional<UrlMapping> findValidByShortCode(@Param("shortCode") String shortCode,
                                               @Param("currentTime") LocalDateTime currentTime);
}




