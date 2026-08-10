package com.shortener.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO for URL shortening request.
 * 
 * Contains the long URL to be shortened and optional metadata
 * such as custom alias, expiration, and tags.
 */
public class ShortenUrlRequest {

    /**
     * The long URL to be shortened.
     * Must be a valid HTTP or HTTPS URL.
     */
    @Pattern(regexp = "^https?://.+", message = "URL must start with http:// or https://")
    private String longUrl;

    /**
     * Optional custom alias for the short code.
     * If provided, must be unique and meet short code format requirements.
     * If not provided, a short code will be auto-generated.
     */
    @Size(min = 4, max = 50, message = "Custom alias must be between 4 and 50 characters")
    @Pattern(regexp = "^[a-zA-Z0-9]+$", message = "Custom alias must contain only alphanumeric characters")
    private String customAlias;

    /**
     * Optional expiration in days from now.
     * If not provided, the URL will never expire.
     */
    private Integer expirationDays;

    /**
     * Optional tags for categorization and filtering.
     */
    private java.util.List<String> tags;

    // Constructors

    public ShortenUrlRequest() {
    }

    public ShortenUrlRequest(String longUrl) {
        this.longUrl = longUrl;
    }

    // Getters and Setters

    public String getLongUrl() {
        return longUrl;
    }

    public void setLongUrl(String longUrl) {
        this.longUrl = longUrl;
    }

    public String getCustomAlias() {
        return customAlias;
    }

    public void setCustomAlias(String customAlias) {
        this.customAlias = customAlias;
    }

    public Integer getExpirationDays() {
        return expirationDays;
    }

    public void setExpirationDays(Integer expirationDays) {
        this.expirationDays = expirationDays;
    }

    public java.util.List<String> getTags() {
        return tags;
    }

    public void setTags(java.util.List<String> tags) {
        this.tags = tags;
    }

    @Override
    public String toString() {
        return "ShortenUrlRequest{" +
                "longUrl='" + longUrl + '\'' +
                ", customAlias='" + customAlias + '\'' +
                ", expirationDays=" + expirationDays +
                ", tags=" + tags +
                '}';
    }
}




