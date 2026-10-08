package com.hrms.exception;

/**
 * Custom Exception for Business Logic Violations
 * 
 * Thrown when business rules are violated:
 * - Insufficient leave balance
 * - Duplicate employee codes
 * - Invalid state transitions
 * - Data validation failures
 * 
 * Automatically handled by GlobalExceptionHandler to return 400 responses.
 */
public class BusinessException extends RuntimeException {
    
    public BusinessException(String message) {
        super(message);
    }
    
    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
