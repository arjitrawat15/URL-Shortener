package com.shortener.entity;

/**
 * Enum representing device types for analytics tracking.
 * 
 * Used to categorize clicks by the type of device used to access the short URL.
 * This helps in understanding user behavior and optimizing for different platforms.
 */
public enum DeviceType {
    /**
     * Mobile device (smartphone, feature phone)
     */
    MOBILE,
    
    /**
     * Desktop or laptop computer
     */
    DESKTOP,
    
    /**
     * Tablet device (iPad, Android tablet, etc.)
     */
    TABLET,
    
    /**
     * Unknown or unrecognized device type
     */
    UNKNOWN
}




