package com.shortener.repository;

import com.shortener.entity.DeviceType;
import com.shortener.entity.UrlAnalytics;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA repository for UrlAnalytics entity.
 * 
 * Provides CRUD operations and analytics query methods for URL click tracking.
 * 
 * Design decisions:
 * - Extends JpaRepository for standard CRUD operations
 * - Custom query methods optimized for analytics use cases
 * - Aggregation queries for statistics (counts, top referrers, etc.)
 * - Time-range queries for time-series analysis
 * - Efficient queries using composite indexes (shortCode + clickTimestamp)
 */
@Repository
public interface UrlAnalyticsRepository extends JpaRepository<UrlAnalytics, Long> {

    /**
     * Finds all analytics records for a specific short code.
     * Returns paginated results for efficient handling of large datasets.
     * 
     * @param shortCode the short code
     * @param pageable pagination parameters
     * @return Page of UrlAnalytics entities
     */
    Page<UrlAnalytics> findByShortCode(String shortCode, Pageable pageable);

    /**
     * Finds analytics records for a short code within a time range.
     * Uses composite index (shortCode, clickTimestamp) for optimal performance.
     * 
     * @param shortCode the short code
     * @param startTime start of the time range (inclusive)
     * @param endTime end of the time range (inclusive)
     * @param pageable pagination parameters
     * @return Page of UrlAnalytics entities
     */
    Page<UrlAnalytics> findByShortCodeAndClickTimestampBetween(String shortCode,
                                                               LocalDateTime startTime,
                                                               LocalDateTime endTime,
                                                               Pageable pageable);

    /**
     * Finds analytics records for a short code within a date range.
     * Uses clickDate index for efficient date-based queries.
     * 
     * @param shortCode the short code
     * @param startDate start of the date range (inclusive)
     * @param endDate end of the date range (inclusive)
     * @param pageable pagination parameters
     * @return Page of UrlAnalytics entities
     */
    Page<UrlAnalytics> findByShortCodeAndClickDateBetween(String shortCode,
                                                          LocalDate startDate,
                                                          LocalDate endDate,
                                                          Pageable pageable);

    /**
     * Counts total clicks for a specific short code.
     * Used for quick statistics without loading all records.
     * 
     * @param shortCode the short code
     * @return total number of clicks
     */
    long countByShortCode(String shortCode);

    /**
     * Counts unique clicks for a short code based on IP address.
     * Uses DISTINCT to count unique IPs.
     * 
     * Note: This is an approximation. For true unique user tracking,
     * consider using cookies or user sessions.
     * 
     * @param shortCode the short code
     * @return count of unique IP addresses
     */
    @Query("SELECT COUNT(DISTINCT a.ipAddress) FROM UrlAnalytics a WHERE a.shortCode = :shortCode")
    long countUniqueClicksByShortCode(@Param("shortCode") String shortCode);

    /**
     * Counts clicks for a short code within a time range.
     * 
     * @param shortCode the short code
     * @param startTime start of the time range (inclusive)
     * @param endTime end of the time range (inclusive)
     * @return count of clicks in the time range
     */
    long countByShortCodeAndClickTimestampBetween(String shortCode,
                                                  LocalDateTime startTime,
                                                  LocalDateTime endTime);

    /**
     * Finds top referrers for a short code.
     * Groups by referrer and orders by count descending.
     * 
     * @param shortCode the short code
     * @param limit maximum number of results
     * @return List of Object arrays: [referrer, count]
     */
    @Query("SELECT a.referrer, COUNT(a) as clickCount " +
           "FROM UrlAnalytics a " +
           "WHERE a.shortCode = :shortCode AND a.referrer IS NOT NULL " +
           "GROUP BY a.referrer " +
           "ORDER BY clickCount DESC")
    List<Object[]> findTopReferrersByShortCode(@Param("shortCode") String shortCode,
                                                Pageable pageable);

    /**
     * Finds top countries for a short code.
     * Groups by country code and orders by count descending.
     * 
     * @param shortCode the short code
     * @param pageable pagination parameters (limit results)
     * @return List of Object arrays: [countryCode, count]
     */
    @Query("SELECT a.countryCode, COUNT(a) as clickCount " +
           "FROM UrlAnalytics a " +
           "WHERE a.shortCode = :shortCode AND a.countryCode IS NOT NULL " +
           "GROUP BY a.countryCode " +
           "ORDER BY clickCount DESC")
    List<Object[]> findTopCountriesByShortCode(@Param("shortCode") String shortCode,
                                                Pageable pageable);

    /**
     * Finds top browsers for a short code.
     * Groups by browser and orders by count descending.
     * 
     * @param shortCode the short code
     * @param pageable pagination parameters
     * @return List of Object arrays: [browser, count]
     */
    @Query("SELECT a.browser, COUNT(a) as clickCount " +
           "FROM UrlAnalytics a " +
           "WHERE a.shortCode = :shortCode AND a.browser IS NOT NULL " +
           "GROUP BY a.browser " +
           "ORDER BY clickCount DESC")
    List<Object[]> findTopBrowsersByShortCode(@Param("shortCode") String shortCode,
                                               Pageable pageable);

    /**
     * Finds device type distribution for a short code.
     * Groups by device type and orders by count descending.
     * 
     * @param shortCode the short code
     * @return List of Object arrays: [deviceType, count]
     */
    @Query("SELECT a.deviceType, COUNT(a) as clickCount " +
           "FROM UrlAnalytics a " +
           "WHERE a.shortCode = :shortCode AND a.deviceType IS NOT NULL " +
           "GROUP BY a.deviceType " +
           "ORDER BY clickCount DESC")
    List<Object[]> findDeviceTypeDistributionByShortCode(@Param("shortCode") String shortCode);

    /**
     * Finds time-series data (clicks per time period) for a short code.
     * Groups by date and orders chronologically.
     * 
     * @param shortCode the short code
     * @param startDate start of the date range (inclusive)
     * @param endDate end of the date range (inclusive)
     * @return List of Object arrays: [clickDate, count]
     */
    @Query("SELECT a.clickDate, COUNT(a) as clickCount " +
           "FROM UrlAnalytics a " +
           "WHERE a.shortCode = :shortCode " +
           "AND a.clickDate BETWEEN :startDate AND :endDate " +
           "GROUP BY a.clickDate " +
           "ORDER BY a.clickDate ASC")
    List<Object[]> findTimeSeriesByShortCode(@Param("shortCode") String shortCode,
                                             @Param("startDate") LocalDate startDate,
                                             @Param("endDate") LocalDate endDate);

    /**
     * Finds time-series data with unique clicks per time period.
     * Groups by date and counts distinct IP addresses.
     * 
     * @param shortCode the short code
     * @param startDate start of the date range (inclusive)
     * @param endDate end of the date range (inclusive)
     * @return List of Object arrays: [clickDate, totalCount, uniqueCount]
     */
    @Query("SELECT a.clickDate, COUNT(a) as totalClicks, COUNT(DISTINCT a.ipAddress) as uniqueClicks " +
           "FROM UrlAnalytics a " +
           "WHERE a.shortCode = :shortCode " +
           "AND a.clickDate BETWEEN :startDate AND :endDate " +
           "GROUP BY a.clickDate " +
           "ORDER BY a.clickDate ASC")
    List<Object[]> findTimeSeriesWithUniqueClicksByShortCode(@Param("shortCode") String shortCode,
                                                             @Param("startDate") LocalDate startDate,
                                                             @Param("endDate") LocalDate endDate);

    /**
     * Deletes analytics records older than a specified date.
     * Used by cleanup jobs to archive or remove old analytics data.
     * 
     * Note: For production, consider archiving before deletion or using
     * table partitioning to drop old partitions instead of deleting rows.
     * 
     * @param cutoffDate records before this date will be deleted
     * @return number of records deleted
     */
    @Modifying
    @Query("DELETE FROM UrlAnalytics a WHERE a.clickDate < :cutoffDate")
    long deleteByClickDateBefore(@Param("cutoffDate") LocalDate cutoffDate);

    /**
     * Finds analytics records by device type.
     * Useful for device-specific analysis.
     * 
     * @param shortCode the short code
     * @param deviceType the device type
     * @param pageable pagination parameters
     * @return Page of UrlAnalytics entities
     */
    Page<UrlAnalytics> findByShortCodeAndDeviceType(String shortCode,
                                                    DeviceType deviceType,
                                                    Pageable pageable);

    /**
     * Finds analytics records by country code.
     * Useful for geographic analysis.
     * 
     * @param shortCode the short code
     * @param countryCode the country code (ISO 3166-1 alpha-2)
     * @param pageable pagination parameters
     * @return Page of UrlAnalytics entities
     */
    Page<UrlAnalytics> findByShortCodeAndCountryCode(String shortCode,
                                                     String countryCode,
                                                     Pageable pageable);
}

