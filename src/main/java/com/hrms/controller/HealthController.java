package com.hrms.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;

/**
 * Health Check Controller
 * 
 * Provides endpoints for monitoring the application status.
 * Used for deployment health checks and status verification.
 */
@RestController
public class HealthController {
    
    @GetMapping("/api/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse(
            "HRMS is running",
            "OK",
            LocalDateTime.now()
        ));
    }
    
    /**
     * Simple health check response DTO
     */
    static class HealthResponse {
        public String message;
        public String status;
        public LocalDateTime timestamp;
        
        public HealthResponse(String message, String status, LocalDateTime timestamp) {
            this.message = message;
            this.status = status;
            this.timestamp = timestamp;
        }
        
        // Getters for JSON serialization
        public String getMessage() { return message; }
        public String getStatus() { return status; }
        public LocalDateTime getTimestamp() { return timestamp; }
    }
}
