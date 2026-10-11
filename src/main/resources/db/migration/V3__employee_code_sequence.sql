-- ============================================================================
-- V3__employee_code_sequence.sql
-- Milestone 2: Atomic Sequences for Transaction-Safe Employee & HR Codes
-- ============================================================================

-- Starts at 3 because EMP-001 and EMP-002 are seeded by DataInitializer
CREATE SEQUENCE IF NOT EXISTS seq_employee_code START WITH 3 INCREMENT BY 1;

-- Starts at 2 because HR-001 is seeded by DataInitializer
CREATE SEQUENCE IF NOT EXISTS seq_hr_code START WITH 2 INCREMENT BY 1;
