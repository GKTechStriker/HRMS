-- V1__initial_schema.sql
-- Flyway Database Migration: Initial Schema Setup
-- 
-- Creates the baseline schema for the HRMS application.
-- Tables will be added progressively as features are implemented:
-- - Week 1-4: Employee, Department, JobRole
-- - Week 2: Attendance, Holiday
-- - Week 3: LeaveType, LeaveBalance, LeaveRequest
-- - Week 0: User (for authentication)

-- Note: This file is initially minimal. Flyway requires at least one migration.
-- Actual tables will be added as we implement features.

-- Create sequence for IDs (if needed for custom ID generation)
-- CREATE SEQUENCE seq_employee_id START 1;
-- CREATE SEQUENCE seq_attendance_id START 1;

-- Placeholder comment for next migrations
-- Users will be created here in subsequent migrations
