package com.shortener.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity representing a URL mapping (short code to long URL).
 * 
 * This is the core entity that stores the relationship between short codes
 * and their corresponding long URLs. It includes metadata such as user ownership,
 * expiration, tags, and click statistics.
 * 
 * Design decisions:
 * - Uses @Table annotation to explicitly set table name and indexes
 * - shortCode has unique constraint and length validation (4-10 chars for Base62)
 * - longUrl uses TEXT type to support URLs of any length
 * - userId is nullable to support anonymous URL shortening
 * - customAlias is optional and unique when provided
 * - tags stored as @ElementCollection for PostgreSQL array support
 * - Automatic timestamp management via JPA lifecycle callbacks
 * - clickCount denormalized for fast reads (updated asynchronously)
 */
@Entity
@Table(
    name = "url_mappings",
    indexes = {
        @Index(name = "idx_short_code", columnList = "short_code", unique = true),
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_custom_alias", columnList = "custom_alias"),
        @Index(name = "idx_created_at", columnList = "created_at"),
        @Index(name = "idx_expires_at", columnList = "expires_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_short_code", columnNames = "short_code"),
        @UniqueConstraint(name = "uk_custom_alias", columnNames = "custom_alias")
    }
)
public class UrlMapping {

    /**
     * Primary key - auto-generated sequential ID.
     * Used internally for relationships and can be converted to Base62 for short codes.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Short code (Base62 encoded) - the public identifier for the URL.
     * Must be unique and between 4-10 characters.
     * This is the key used in redirect lookups.
     * 
     * Note: This field is nullable during initial save (to get auto-generated ID),
     * but must be set before the final save. The database column allows null initially,
     * but the service ensures it's always set before completion.
     */
    @Size(min = 4, max = 10, message = "Short code must be between 4 and 10 characters")
    @Pattern(regexp = "^[a-zA-Z0-9]+$", message = "Short code must contain only alphanumeric characters")
    @Column(name = "short_code", nullable = true, unique = true, length = 10)
    private String shortCode;

    /**
     * Original long URL that was shortened.
     * Stored as TEXT to support URLs of any length.
     * Validated to ensure it starts with http:// or https://
     */
    @NotBlank(message = "Long URL cannot be blank")
    @Pattern(regexp = "^https?://.+", message = "URL must start with http:// or https://")
    @Column(name = "long_url", nullable = false, columnDefinition = "TEXT")
    private String longUrl;

    /**
     * User ID of the creator (nullable for anonymous users).
     * Foreign key reference to users table (if authentication is implemented).
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * Optional custom alias provided by the user.
     * If provided, must be unique. If null, shortCode is auto-generated.
     * Allows users to create memorable short URLs.
     */
    @Size(max = 50, message = "Custom alias must not exceed 50 characters")
    @Column(name = "custom_alias", unique = true, length = 50)
    private String customAlias;

    /**
     * Whether the URL mapping is active.
     * Inactive URLs return 410 Gone instead of redirecting.
     * Allows soft deletion without losing analytics data.
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * Expiration timestamp (nullable for never-expiring URLs).
     * URLs with expiresAt in the past are considered expired.
     * Used by cleanup jobs to identify stale URLs.
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * Timestamp when the URL mapping was created.
     * Automatically set on entity creation.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when the URL mapping was last updated.
     * Automatically updated on entity modification.
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Total click count (denormalized for performance).
     * Updated asynchronously from analytics table to avoid blocking redirects.
     * Used for quick statistics without querying analytics table.
     */
    @Column(name = "click_count", nullable = false)
    private Long clickCount = 0L;

    /**
     * Tags associated with the URL for categorization and filtering.
     * Stored as @ElementCollection to leverage PostgreSQL array type.
     * Indexed with GIN index for efficient tag-based queries.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "url_mapping_tags",
        joinColumns = @JoinColumn(name = "url_mapping_id"),
        indexes = @Index(name = "idx_url_mapping_tags", columnList = "tags")
    )
    @Column(name = "tags", length = 50)
    @OrderColumn
    private List<String> tags = new ArrayList<>();

    // Constructors

    public UrlMapping() {
        // Default constructor required by JPA
    }

    public UrlMapping(String shortCode, String longUrl) {
        this.shortCode = shortCode;
        this.longUrl = longUrl;
        this.isActive = true;
        this.clickCount = 0L;
    }

    // Business logic methods

    /**
     * Checks if the URL mapping is expired.
     * @return true if expiresAt is not null and in the past
     */
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    /**
     * Checks if the URL mapping is valid (active and not expired).
     * @return true if the URL can be used for redirection
     */
    public boolean isValid() {
        return isActive && !isExpired();
    }

    /**
     * Increments the click count atomically.
     * Note: For production, consider using database-level atomic increment
     * to avoid race conditions in high-concurrency scenarios.
     */
    public void incrementClickCount() {
        this.clickCount++;
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

    public String getLongUrl() {
        return longUrl;
    }

    public void setLongUrl(String longUrl) {
        this.longUrl = longUrl;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getCustomAlias() {
        return customAlias;
    }

    public void setCustomAlias(String customAlias) {
        this.customAlias = customAlias;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getClickCount() {
        return clickCount;
    }

    public void setClickCount(Long clickCount) {
        this.clickCount = clickCount;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    @Override
    public String toString() {
        return "UrlMapping{" +
                "id=" + id +
                ", shortCode='" + shortCode + '\'' +
                ", longUrl='" + longUrl + '\'' +
                ", userId=" + userId +
                ", isActive=" + isActive +
                ", clickCount=" + clickCount +
                ", createdAt=" + createdAt +
                '}';
    }
}

