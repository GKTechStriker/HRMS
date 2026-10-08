package com.hrms.exception;

/**
 * Custom Exception for Resource Not Found scenarios
 * 
 * Thrown when a requested resource (Employee, Department, etc.) does not exist.
 * Automatically handled by GlobalExceptionHandler to return 404 responses.
 */
public class ResourceNotFoundException extends RuntimeException {
    
    public ResourceNotFoundException(String message) {
        super(message);
    }
    
    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public static ResourceNotFoundException forEntity(String entityName, String identifier) {
        return new ResourceNotFoundException(entityName + " not found: " + identifier);
    }
}
