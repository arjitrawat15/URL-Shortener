package com.shortener.entity;


import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity representing analytics data for URL clicks.
 * 
 * This entity stores detailed information about each click on a short URL,
 * including metadata such as IP address, user agent, referrer, geographic
 * location, and device information.
 * 
 * Design decisions:
 * - Uses @ManyToOne relationship to UrlMapping for referential integrity
 * - clickDate is a generated column (computed from clickTimestamp) for partitioning
 * - IP address stored as String (PostgreSQL INET type handled by columnDefinition)
 * - Device type uses enum for type safety and consistency
 * - Country code follows ISO 3166-1 alpha-2 standard (2 characters)
 * - Composite index on (shortCode, clickTimestamp) for efficient time-range queries
 * - Foreign key with CASCADE DELETE to automatically clean up analytics when URL is deleted
 * 
 * Note: For production scale, this table should be partitioned by click_date
 * (monthly or weekly partitions). Partitioning is typically handled at the
 * database level via DDL scripts rather than JPA annotations.
 */
@Entity
@Table(
    name = "url_analytics",
    indexes = {
        @Index(name = "idx_short_code_timestamp", columnList = "short_code,click_timestamp"),
        @Index(name = "idx_click_date", columnList = "click_date"),
        @Index(name = "idx_country_code", columnList = "country_code")
    }
)
public class UrlAnalytics {

    /**
     * Primary key - auto-generated sequential ID.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Short code of the clicked URL.
     * Foreign key reference to url_mappings table.
     * Uses @ManyToOne for referential integrity and cascade delete.
     */
    @NotBlank(message = "Short code cannot be blank")
    @Size(min = 4, max = 10, message = "Short code must be between 4 and 10 characters")
    @Column(name = "short_code", nullable = false, length = 10)
    private String shortCode;

    /**
     * Many-to-one relationship to UrlMapping.
     * Optional = false ensures every analytics record has a valid URL mapping.
     * Cascade DELETE ensures analytics are cleaned up when URL is deleted.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "short_code",
        referencedColumnName = "short_code",
        insertable = false,
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_short_code")
    )
    private UrlMapping urlMapping;

    /**
     * Timestamp when the click occurred.
     * Automatically set to current timestamp on creation.
     * Used for time-series analysis and partitioning.
     */
    @CreationTimestamp
    @Column(name = "click_timestamp", nullable = false, updatable = false)
    private LocalDateTime clickTimestamp;

    /**
     * Date extracted from clickTimestamp (generated column).
     * Used for time-based partitioning and date-range queries.
     * In PostgreSQL, this would be: click_date DATE GENERATED ALWAYS AS (DATE(click_timestamp)) STORED
     * 
     * Note: JPA doesn't directly support generated columns, so this is computed
     * in application code or via database triggers. For production, consider
     * using a database view or computed column at the database level.
     */
    @Column(name = "click_date", nullable = false, updatable = false)
    private LocalDate clickDate;

    /**
     * IP address of the client (IPv4 or IPv6).
     * Stored as String; PostgreSQL INET type handled via columnDefinition.
     * Used for geolocation and fraud detection.
     */
    @Column(name = "ip_address", columnDefinition = "INET")
    private String ipAddress;

    /**
     * User-Agent string from the HTTP request.
     * Contains information about browser, OS, and device.
     * Parsed to extract browser, OS, and device type.
     */
    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    /**
     * HTTP Referer header value.
     * Indicates where the user came from (search engine, social media, etc.).
     * NULL for direct visits (no referrer).
     */
    @Column(name = "referrer", columnDefinition = "TEXT")
    private String referrer;

    /**
     * Country code (ISO 3166-1 alpha-2 format, e.g., "US", "GB").
     * Extracted from IP address using GeoIP database.
     * Used for geographic analytics and compliance (GDPR, etc.).
     */
    @Size(min = 2, max = 2, message = "Country code must be exactly 2 characters")
    @Column(name = "country_code", length = 2)
    private String countryCode;

    /**
     * Type of device used (mobile, desktop, tablet, unknown).
     * Extracted from User-Agent string.
     * Used for device-specific analytics and optimization.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", length = 20)
    private DeviceType deviceType;

    /**
     * Browser name (e.g., "Chrome", "Firefox", "Safari").
     * Extracted from User-Agent string.
     * Used for browser compatibility analysis.
     */
    @Column(name = "browser", length = 50)
    private String browser;

    /**
     * Operating system (e.g., "Windows", "macOS", "Linux", "iOS", "Android").
     * Extracted from User-Agent string.
     * Used for platform-specific analytics.
     */
    @Column(name = "os", length = 50)
    private String os;

    // Constructors

    public UrlAnalytics() {
        // Default constructor required by JPA
    }

    public UrlAnalytics(String shortCode) {
        this.shortCode = shortCode;
        this.clickDate = LocalDate.now();
    }

    // Business logic methods

    /**
     * Sets clickTimestamp and automatically updates clickDate.
     * In production, this would be handled by database triggers or computed columns.
     */
    @PrePersist
    protected void onPrePersist() {
        if (clickTimestamp == null) {
            clickTimestamp = LocalDateTime.now();
        }
        if (clickDate == null && clickTimestamp != null) {
            clickDate = clickTimestamp.toLocalDate();
        }
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public UrlMapping getUrlMapping() {
        return urlMapping;
    }

    public void setUrlMapping(UrlMapping urlMapping) {
        this.urlMapping = urlMapping;
    }

    public LocalDateTime getClickTimestamp() {
        return clickTimestamp;
    }

    public void setClickTimestamp(LocalDateTime clickTimestamp) {
        this.clickTimestamp = clickTimestamp;
        // Update clickDate when timestamp changes
        if (clickTimestamp != null) {
            this.clickDate = clickTimestamp.toLocalDate();
        }
    }

    public LocalDate getClickDate() {
        return clickDate;
    }

    public void setClickDate(LocalDate clickDate) {
        this.clickDate = clickDate;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getReferrer() {
        return referrer;
    }

    public void setReferrer(String referrer) {
        this.referrer = referrer;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(DeviceType deviceType) {
        this.deviceType = deviceType;
    }

    public String getBrowser() {
        return browser;
    }

    public void setBrowser(String browser) {
        this.browser = browser;
    }

    public String getOs() {
        return os;
    }

    public void setOs(String os) {
        this.os = os;
    }

    @Override
    public String toString() {
        return "UrlAnalytics{" +
                "id=" + id +
                ", shortCode='" + shortCode + '\'' +
                ", clickTimestamp=" + clickTimestamp +
                ", countryCode='" + countryCode + '\'' +
                ", deviceType=" + deviceType +
                ", browser='" + browser + '\'' +
                '}';
    }
}




