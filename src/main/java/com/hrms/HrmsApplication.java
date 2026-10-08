package com.hrms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main Spring Boot Application Entry Point
 * 
 * Runs as the root Spring Boot application with automatic component scanning
 * in the com.hrms package and all sub-packages.
 */
@SpringBootApplication
public class HrmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(HrmsApplication.class, args);
    }
}
