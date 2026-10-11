-- ============================================================================
-- V2__core_schema.sql
-- Phase 1 (Weeks 1-2): Core Foundation Schema
-- Tables:
--   1. SPRING_SESSION & SPRING_SESSION_ATTRIBUTES (Spring Session JDBC)
--   2. users (authentication & role-based access)
--   3. user_sessions (companion session tracking for oversight & revocation)
--   4. audit_log (append-only, tamper-evident action history)
--   5. departments, job_roles, employees (core HR entities)
-- ============================================================================

-- 1. Spring Session JDBC Tables (PostgreSQL)
CREATE TABLE IF NOT EXISTS SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX IF NOT EXISTS SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE IF NOT EXISTS SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);

-- 2. users — Authentication and System Access (Section 5.1)
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role VARCHAR(32) NOT NULL CHECK (role IN ('SUPER_ADMIN', 'HR_ADMIN', 'EMPLOYEE')),
    hr_code VARCHAR(20) UNIQUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    password_reset_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_users_hr_code ON users(hr_code);

-- 3. user_sessions — Companion Session Oversight Table (Section 5.1)
CREATE TABLE user_sessions (
    id UUID PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id),
    ip_address VARCHAR(45) NOT NULL,
    user_agent TEXT NOT NULL,
    login_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    logout_at TIMESTAMPTZ,
    ended_reason VARCHAR(32) CHECK (ended_reason IS NULL OR ended_reason IN ('LOGOUT', 'EXPIRED', 'REVOKED')),
    revoked_by UUID REFERENCES users(id)
);

CREATE INDEX idx_user_sessions_user_id ON user_sessions(user_id);
CREATE INDEX idx_user_sessions_login_at ON user_sessions(login_at DESC);

-- 4. audit_log — Append-Only Action History (Section 5.1)
CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    hr_code VARCHAR(20),
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_id UUID NOT NULL,
    previous_data JSONB,
    new_data JSONB,
    ip_address VARCHAR(45) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_user_id ON audit_log(user_id);
CREATE INDEX idx_audit_log_action ON audit_log(action);
CREATE INDEX idx_audit_log_target ON audit_log(target_type, target_id);
CREATE INDEX idx_audit_log_occurred_at ON audit_log(occurred_at DESC);

-- Enforce Append-Only Immutability on audit_log at Database Engine Level (Section 5.1)
CREATE OR REPLACE FUNCTION prevent_audit_log_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_log table is append-only. UPDATE and DELETE operations are prohibited.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_immutable
BEFORE UPDATE OR DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_modification();

REVOKE UPDATE, DELETE ON audit_log FROM PUBLIC;

-- 5. departments — Organizational Structure (Section 5.2)
CREATE TABLE departments (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    head_employee_id UUID,
    parent_department_id UUID REFERENCES departments(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_departments_parent ON departments(parent_department_id);

-- 6. job_roles — Job Designations (Section 5.2)
CREATE TABLE job_roles (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    level INT NOT NULL DEFAULT 1,
    department_id UUID NOT NULL REFERENCES departments(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_job_role_title_dept UNIQUE (title, department_id)
);

CREATE INDEX idx_job_roles_department ON job_roles(department_id);

-- 7. employees — HR Profile Data (Section 5.1)
CREATE TABLE employees (
    id UUID PRIMARY KEY,
    user_id UUID UNIQUE REFERENCES users(id),
    department_id UUID NOT NULL REFERENCES departments(id),
    job_role_id UUID NOT NULL REFERENCES job_roles(id),
    reports_to UUID REFERENCES employees(id),
    employee_code VARCHAR(20) NOT NULL UNIQUE,
    full_name VARCHAR(255) NOT NULL,
    phone VARCHAR(20),
    date_of_birth DATE,
    date_of_joining DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ON_LEAVE', 'INACTIVE')),
    profile_photo_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_employees_department ON employees(department_id);
CREATE INDEX idx_employees_job_role ON employees(job_role_id);
CREATE INDEX idx_employees_status ON employees(status);
CREATE INDEX idx_employees_reports_to ON employees(reports_to);

-- Add deferred FK from departments.head_employee_id to employees(id)
ALTER TABLE departments
    ADD CONSTRAINT fk_departments_head_employee
    FOREIGN KEY (head_employee_id) REFERENCES employees(id);
