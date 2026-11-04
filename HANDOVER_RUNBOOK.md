# Enterprise HRMS — System Architecture & Handover Runbook v2.0

> **Platform**: Java 25 · Spring Boot 4.1.0 · PostgreSQL 16+ · Spring Data JPA · Flyway · Thymeleaf · Tailwind CSS · Chart.js  
> **Repository**: `/home/techstriker/HRMS`  
> **Server URL**: `http://localhost:8080`

---

## 1. System Architecture & Component Overview

```
+-----------------------------------------------------------------------------------+
|                                Client Layer                                       |
|   Thymeleaf 3.1 + Tailwind CSS + Chart.js (Visual Analytics & Export Controls)   |
+-----------------------------------------------------------------------------------+
                                         │  (HTTP / Session Cookies)
                                         ▼
+-----------------------------------------------------------------------------------+
|                        Spring Security 6 Authorization Filter                     |
|  - Role-Based Access Control (SUPER_ADMIN, HR_ADMIN, EMPLOYEE)                   |
|  - Spring Session JDBC (PostgreSQL-backed session store & revocation)             |
|  - SUPER_ADMIN Zero-Write Constraint: blocks POST /employees, /leaves, etc.       |
+-----------------------------------------------------------------------------------+
                                         │
        ┌────────────────────────────────┼────────────────────────────────┐
        ▼                                ▼                                ▼
+──────────────────+           +──────────────────+             +──────────────────+
| Employee & Org   |           | Attendance &     |             | Leave Engine &   |
| Module           |           | Shift Finalizer  |             | Balance Quotas   |
| - Atomic seq     |           | - Clock In/Out   |             | - Pessimistic lock|
| - Dry-run CSV    |           | - 03:00 AM Cron  |             | - Overlap guards |
| - Directory CRUD |           | - @Audited fix   |             | - Auto-sync att. |
+──────────────────+           +──────────────────+             +──────────────────+
        │                                │                                │
        └────────────────────────────────┼────────────────────────────────┘
                                         ▼
+-----------------------------------------------------------------------------------+
|                    Append-Only Immutable Audit Subsystem                          |
|  - Declarative AOP Aspect (`@Audited`) capturing Actor, HR Code, JSONB snapshots  |
|  - PostgreSQL Database Trigger `trg_audit_log_immutable` blocks UPDATE & DELETE  |
+-----------------------------------------------------------------------------------+
                                         │
                                         ▼
+-----------------------------------------------------------------------------------+
|                    PostgreSQL 16+ Database (`hrms_springboot`)                    |
|  - Flyway Migrations V1__init.sql through V4__attendance_and_leave.sql            |
+-----------------------------------------------------------------------------------+
```

---

## 2. Seed Credentials & Role Boundaries

All seed users are automatically provisioned during database initialization via Flyway and `DataInitializer.java`. Default password is configured in `.env` / `application.yml` (`Password@123`).

| Email | Role | HR Code | Scope & Responsibilities |
| :--- | :--- | :--- | :--- |
| `admin@company.com` | `ROLE_SUPER_ADMIN` | — | **System Oversight & Auditing**: Complete read-only visibility across all employees, departments, attendance rosters, leave queues, and visual analytics. Can revoke active sessions. **Strictly zero-write on HR domain data** (all mutation attempts return HTTP 403 Forbidden). |
| `hr1@company.com` | `ROLE_HR_ADMIN` | `HR-001` | **HR Operations**: Employee creation & lifecycle management, two-step dry-run CSV bulk import, manual attendance corrections (audited), leave application approval/rejection, CSV exports. |
| `emp1@company.com` | `ROLE_EMPLOYEE` | — | **Employee Self-Service**: Personal profile viewing, daily clock-in/out, leave application, personal quota tracking. Strictly forbidden from administrative management views. |

---

## 3. Core Architectural Safeguards & Hardening

### 3.1 SUPER_ADMIN Zero-Write Boundary (Section 9.4)
- **Security Filter Chain**: [SecurityConfig.java](file:///home/techstriker/HRMS/src/main/java/com/hrms/config/SecurityConfig.java) restricts mutation paths (`POST /employees/**`, `POST /departments/**`, `POST /attendance/correct`, `POST /leaves/*/approve`) strictly to `HR_ADMIN`.
- **Method-Level Security**: Controllers enforce `@PreAuthorize("hasRole('HR_ADMIN')")`.
- **UI Guard**: Thymeleaf templates use `sec:authorize="hasRole('HR_ADMIN')"` so zero write controls or mutation forms are rendered into the DOM for Super Admin.
- **Verification**: Verified by automated test suite [`RoleBoundarySecurityTest`](file:///home/techstriker/HRMS/src/test/java/com/hrms/security/RoleBoundarySecurityTest.java).

### 3.2 Append-Only Immutable Audit Trail
- **PostgreSQL Trigger**: `trg_audit_log_immutable` is installed on table `audit_log`, executing `RAISE EXCEPTION` on any `UPDATE` or `DELETE` statement.
- **AOP Interception**: Methods annotated with `@Audited` automatically capture:
  - Timestamp (`occurred_at`)
  - Authenticated user ID & HR Code (`hr_code`)
  - Target entity type and UUID
  - Before and After state snapshots in PostgreSQL `jsonb`
  - Spring Session ID and client IP address

### 3.3 Concurrency & Race-Condition Safe Leave Engine
- **Pessimistic Locking**: `LeaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdWithLock()` executes `SELECT ... FOR UPDATE` ensuring quota deductions cannot race under concurrent requests.
- **Overlap Prevention**: Enforces `existsOverlappingLeave()` preventing double-booking the same calendar day.
- **Attendance Auto-Sync**: Approved leave dates automatically insert/update corresponding daily records in `attendance_records` with status `ON_LEAVE`.
- **Verification**: Verified by [`LeaveConcurrencyTest`](file:///home/techstriker/HRMS/src/test/java/com/hrms/leave/LeaveConcurrencyTest.java).

### 3.4 Atomic Employee Code Sequencing
- PostgreSQL sequences `seq_employee_code` and `seq_hr_code` guarantee gapless, collision-free codes (`EMP-001`, `HR-001`) even under high concurrency.
- **Verification**: Verified by [`EmployeeCodeConcurrencyTest`](file:///home/techstriker/HRMS/src/test/java/com/hrms/employee/EmployeeCodeConcurrencyTest.java).

---

## 4. Endpoints & Route Directory

### Authentication & Dashboard
- `GET /login` — Authentication page with pre-filled test persona hints
- `POST /logout` — Invalidate session and record session termination
- `GET /dashboard` — Role-differentiated dashboard:
  - **Super Admin & HR Admin**: Headline KPIs, Department Headcount Doughnut Chart, 14-Day Attendance Trends Stacked Bar Chart, Recent Audit Trail.
  - **Employee**: Punch clock status, quick leave balance summary, personal profile.
- `GET /sessions` — Live active session viewer (Spring Session JDBC)
- `POST /sessions/{id}/revoke` — Revoke live session

### Visual Analytics Endpoints (Milestone 4A)
- `GET /api/analytics/headcount-by-dept` — JSON distribution of active headcount by department
- `GET /api/analytics/attendance-trends?days=14` — 14-day rolling continuous status counts
- `GET /api/analytics/leave-usage` — Cumulative approved leave days grouped by leave category

### Tabular Reporting & RFC-4180 CSV Exports (Milestone 4B)
- `GET /reports/employees/csv` — Full employee directory export
- `GET /reports/attendance/csv?date=YYYY-MM-DD&departmentId=...` — Daily roster export
- `GET /reports/audit/csv` — Immutable audit log export (scoped by role)

### Employee Management
- `GET /employees` — Searchable employee directory (filter by department, role, status, text)
- `GET /employees/new` — Onboarding form (`HR_ADMIN` only)
- `POST /employees` — Persist new employee (`HR_ADMIN` only)
- `GET /employees/{id}/edit` — Edit employee form (`HR_ADMIN` only)
- `POST /employees/{id}` — Update employee (`HR_ADMIN` only)
- `GET /employees/import` — CSV Bulk Import UI with Dry-Run mode (`HR_ADMIN` only)
- `POST /employees/import` — Execute live import or dry-run validation (`HR_ADMIN` only)

### Attendance & Daily Punches
- `GET /attendance/my` — Self-service clock in / clock out interface
- `POST /attendance/clock-in` — Record daily check-in with work mode (WFO, WFH, REMOTE)
- `POST /attendance/clock-out` — Record daily check-out and compute worked duration
- `GET /attendance/manage` — Daily attendance roster oversight
- `POST /attendance/correct` — Audited manual adjustment (`HR_ADMIN` only)

### Leave Management
- `GET /leaves/my` — Self-service leave application form & balance summary
- `POST /leaves/apply` — Submit leave request with quota validation
- `GET /leaves/manage` — Pending approval queue & organization leave history
- `POST /leaves/{id}/approve` — Approve request & auto-sync attendance (`HR_ADMIN` only)
- `POST /leaves/{id}/reject` — Reject request with documented reason (`HR_ADMIN` only)

---

## 5. Operations, Maintenance & Database Runbook

### 5.1 Build & Test Execution
```bash
# Compile and package application
./mvnw clean package -DskipTests=false

# Run complete test suite (Role boundary tests, concurrency tests, service tests)
./mvnw test

# Start the Spring Boot Application
./mvnw spring-boot:run
```

### 5.2 Database Migrations (Flyway)
Flyway migrations are executed automatically on application startup.
- Migration files: `src/main/resources/db/migration/`
  - `V1__init.sql`: Core schema (users, roles, sessions, departments, job roles, employees, audit log)
  - `V2__audit_trigger.sql`: PostgreSQL immutable audit trigger
  - `V3__sequences.sql`: Native PostgreSQL sequence generators
  - `V4__attendance_and_leave.sql`: Attendance records, holidays, leave types, balances, requests

To inspect migration status:
```bash
psql -U hrms_spring -d hrms_springboot -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

### 5.3 Backup & Disaster Recovery
```bash
# Create complete PostgreSQL database dump
pg_dump -U hrms_spring -h localhost -d hrms_springboot -F c -b -v -f /backup/hrms_springboot_$(date +%Y%m%d_%H%M%S).dump

# Restore database from dump
pg_restore -U hrms_spring -h localhost -d hrms_springboot -v --clean /backup/hrms_springboot_YYYYMMDD_HHMMSS.dump
```

---

## 6. Acceptance & Delivery Verification Checklist

| Milestone Phase | Deliverable | Status |
| :--- | :--- | :--- |
| **Milestone 1** | Spring Security, BCrypt, Session JDBC, Append-Only Immutable Audit Trigger, `@Audited` Aspect | Verified |
| **Milestone 2** | Atomic sequences, Org Structure CRUD, Employee Directory, 2-Step Dry-Run CSV Bulk Import | Verified |
| **Milestone 3A** | Workday punch engine, Nightly finalizer cron, Audited manual correction, Attendance views | Verified |
| **Milestone 3B** | Annual leave quotas, Pessimistic lock hold-then-convert, Attendance auto-sync, Concurrency suite | Verified |
| **Milestone 4A** | Analytics engine, Live Chart.js Visual Endpoints (Headcount Doughnut, 14-Day Attendance Bar) | Delivered |
| **Milestone 4B** | RFC-4180 CSV Export engine (Employees, Attendance roster, Audited log), Download triggers | Delivered |
| **Milestone 4C** | System Hardening & Role Boundary Verification Suite (`RoleBoundarySecurityTest`) | Delivered |
| **Milestone 4D** | System Architecture & Operations Handover Runbook (`HANDOVER_RUNBOOK.md`) | Delivered |
