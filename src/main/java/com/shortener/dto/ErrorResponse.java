package com.shortener.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for error responses.
 * 
 * Provides a standardized error response format across all API endpoints.
 */
public class ErrorResponse {

    /**
     * HTTP status code.
     */
    private int status;

    /**
     * Error message describing what went wrong.
     */
    private String message;

    /**
     * Timestamp when the error occurred.
     */
    private LocalDateTime timestamp;

    /**
     * Detailed error message or stack trace (only in development).
     */
    private String details;

    /**
     * List of validation errors (for 400 Bad Request).
     */
    private List<FieldError> errors;

    // Constructors

    public ErrorResponse() {
        this.timestamp = LocalDateTime.now();
    }

    public ErrorResponse(int status, String message) {
        this();
        this.status = status;
        this.message = message;
    }

    public ErrorResponse(int status, String message, String details) {
        this(status, message);
        this.details = details;
    }

    // Getters and Setters

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public List<FieldError> getErrors() {
        return errors;
    }

    public void setErrors(List<FieldError> errors) {
        this.errors = errors;
    }

    /**
     * Nested class for field-level validation errors.
     */
    public static class FieldError {
        private String field;
        private String message;
        private Object rejectedValue;

        public FieldError() {
        }

        public FieldError(String field, String message, Object rejectedValue) {
            this.field = field;
            this.message = message;
            this.rejectedValue = rejectedValue;
        }

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Object getRejectedValue() {
            return rejectedValue;
        }

        public void setRejectedValue(Object rejectedValue) {
            this.rejectedValue = rejectedValue;
        }
    }
}




