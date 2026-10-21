-- ============================================================================
-- V4__attendance_and_leave.sql
-- Milestone 3: Daily Operations (Attendance & Leave Management)
-- Tables:
--   1. holidays (company calendar)
--   2. attendance_records (clock-in/out, hours, manual corrections, status)
--   3. leave_types (annual, sick, casual, unpaid)
--   4. leave_balances (per-employee annual quota, used, pending, remaining)
--   5. leave_requests (time-off applications with provisional hold status)
-- ============================================================================

-- 1. holidays — Company & Public Holidays
CREATE TABLE holidays (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    holiday_date DATE NOT NULL UNIQUE,
    is_recurring BOOLEAN NOT NULL DEFAULT FALSE,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_holidays_date ON holidays(holiday_date);

-- 2. attendance_records — Daily Employee Attendance & Punches
CREATE TABLE attendance_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    work_date DATE NOT NULL,
    clock_in TIMESTAMPTZ,
    clock_out TIMESTAMPTZ,
    duration_minutes INT,
    status VARCHAR(32) NOT NULL DEFAULT 'PRESENT' CHECK (status IN ('PRESENT', 'LATE', 'HALF_DAY', 'ABSENT', 'ON_LEAVE', 'HOLIDAY')),
    work_mode VARCHAR(32) NOT NULL DEFAULT 'ON_SITE' CHECK (work_mode IN ('ON_SITE', 'REMOTE')),
    notes TEXT,
    clock_in_ip VARCHAR(45),
    clock_out_ip VARCHAR(45),
    is_manually_corrected BOOLEAN NOT NULL DEFAULT FALSE,
    corrected_by UUID REFERENCES users(id),
    correction_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_employee_work_date UNIQUE (employee_id, work_date)
);

CREATE INDEX idx_attendance_employee_date ON attendance_records(employee_id, work_date DESC);
CREATE INDEX idx_attendance_work_date_status ON attendance_records(work_date, status);

-- 3. leave_types — Configurable Leave Categories
CREATE TABLE leave_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    default_days_per_year NUMERIC(4,1) NOT NULL DEFAULT 0,
    is_paid BOOLEAN NOT NULL DEFAULT TRUE,
    requires_attachment BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_leave_types_code ON leave_types(code);

-- 4. leave_balances — Annual Quota Allocation & Tracking
CREATE TABLE leave_balances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    leave_type_id UUID NOT NULL REFERENCES leave_types(id) ON DELETE RESTRICT,
    year INT NOT NULL,
    allocated_days NUMERIC(4,1) NOT NULL DEFAULT 0,
    used_days NUMERIC(4,1) NOT NULL DEFAULT 0,
    pending_days NUMERIC(4,1) NOT NULL DEFAULT 0,
    remaining_days NUMERIC(4,1) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_employee_leave_type_year UNIQUE (employee_id, leave_type_id, year)
);

CREATE INDEX idx_leave_balances_employee_year ON leave_balances(employee_id, year);

-- 5. leave_requests — Time-Off Applications with Provisional Hold
CREATE TABLE leave_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    leave_type_id UUID NOT NULL REFERENCES leave_types(id) ON DELETE RESTRICT,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    total_days NUMERIC(4,1) NOT NULL,
    reason TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    actioned_by UUID REFERENCES users(id),
    actioned_at TIMESTAMPTZ,
    rejection_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_leave_requests_employee ON leave_requests(employee_id, status);
CREATE INDEX idx_leave_requests_dates ON leave_requests(start_date, end_date);
CREATE INDEX idx_leave_requests_status ON leave_requests(status);

-- Baseline Seed Data for Leave Types
INSERT INTO leave_types (id, code, name, default_days_per_year, is_paid, requires_attachment) VALUES
    ('10000000-0000-0000-0000-000000000001', 'ANNUAL', 'Annual / Privilege Leave', 15.0, true, false),
    ('10000000-0000-0000-0000-000000000002', 'SICK', 'Sick Leave', 10.0, true, false),
    ('10000000-0000-0000-0000-000000000003', 'CASUAL', 'Casual Leave', 7.0, true, false),
    ('10000000-0000-0000-0000-000000000004', 'UNPAID', 'Leave Without Pay', 0.0, false, false)
ON CONFLICT (code) DO NOTHING;

-- Baseline Seed Data for Holidays (2026 Sample Calendar)
INSERT INTO holidays (name, holiday_date, is_recurring, description) VALUES
    ('New Year''s Day', '2026-01-01', true, 'Global New Year celebration'),
    ('Republic Day', '2026-01-26', true, 'National Holiday'),
    ('Holi', '2026-03-04', false, 'Festival of Colors'),
    ('Good Friday', '2026-04-03', false, 'Public Holiday'),
    ('Independence Day', '2026-08-15', true, 'National Independence Day'),
    ('Mahatma Gandhi Jayanti', '2026-10-02', true, 'National Holiday'),
    ('Diwali', '2026-11-08', false, 'Festival of Lights'),
    ('Christmas Day', '2026-12-25', true, 'Christmas Holiday')
ON CONFLICT (holiday_date) DO NOTHING;
