package com.shortener.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for URL shortening response.
 * 
 * Contains the generated short code, short URL, and metadata
 * about the created URL mapping.
 */
public class ShortenUrlResponse {

    /**
     * The generated or custom short code.
     */
    private String shortCode;

    /**
     * The complete short URL (base URL + short code).
     */
    private String shortUrl;

    /**
     * The original long URL that was shortened.
     */
    private String longUrl;

    /**
     * Timestamp when the URL was created.
     */
    private LocalDateTime createdAt;

    /**
     * Timestamp when the URL will expire (null if never expires).
     */
    private LocalDateTime expiresAt;

    /**
     * Tags associated with the URL.
     */
    private List<String> tags;

    /**
     * QR code URL for the short URL (optional, can be generated separately).
     */
    private String qrCode;

    // Constructors

    public ShortenUrlResponse() {
    }

    public ShortenUrlResponse(String shortCode, String shortUrl, String longUrl) {
        this.shortCode = shortCode;
        this.shortUrl = shortUrl;
        this.longUrl = longUrl;
    }

    // Getters and Setters

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public String getShortUrl() {
        return shortUrl;
    }

    public void setShortUrl(String shortUrl) {
        this.shortUrl = shortUrl;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public void setLongUrl(String longUrl) {
        this.longUrl = longUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public String getQrCode() {
        return qrCode;
    }

    public void setQrCode(String qrCode) {
        this.qrCode = qrCode;
    }

    @Override
    public String toString() {
        return "ShortenUrlResponse{" +
                "shortCode='" + shortCode + '\'' +
                ", shortUrl='" + shortUrl + '\'' +
                ", longUrl='" + longUrl + '\'' +
                ", createdAt=" + createdAt +
                ", expiresAt=" + expiresAt +
                '}';
    }
}




