# Enterprise HRMS — System Architecture & Handover Runbook v2.0

> **Platform**: Java 25 · Spring Boot 4.1.0 · PostgreSQL 16+ · Spring Data JPA · Flyway · Thymeleaf · Tailwind CSS · Chart.js · Docker Compose  
> **Repository**: `GKTechStriker/HRMS`  
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
|  - Absolute Session Lifetime Filter (8-hour maximum age enforcement)              |
+-----------------------------------------------------------------------------------+
                                         │
        ┌────────────────────────────────┼────────────────────────────────┐
        ▼                                ▼                                ▼
+──────────────────+           +──────────────────+             +──────────────────+
| Employee & Org   |           | Attendance &     |             | Leave Engine &   |
| Module           |           | Shift Finalizer  |             | Balance Quotas   |
| - Atomic seq     |           | - Clock In/Out   |             | - Pessimistic lock|
| - Dry-run CSV    |           | - 23:59 Mon-Fri  |             | - Overlap guards |
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

All seed users are automatically provisioned during database initialization via Flyway and `DataInitializer.java`. Default password is configurable via `.env` / `HRMS_DEFAULT_USER_PASSWORD`.

| Email | Role | HR Code | Scope & Responsibilities |
| :--- | :--- | :--- | :--- |
| `superadmin@company.com` | `ROLE_SUPER_ADMIN` | — | **System Oversight & Auditing**: Complete read-only visibility across all employees, departments, attendance rosters, leave queues, company holidays, and visual analytics. Can revoke active sessions. **Strictly zero-write on HR domain data** (all mutation attempts return HTTP 403 Forbidden). |
| `hr1@company.com` | `ROLE_HR_ADMIN` | `HR-001` | **HR Operations**: Employee creation & lifecycle management, two-step dry-run CSV bulk import, manual attendance corrections (audited), leave application approval/rejection, company holiday calendar management, CSV streaming exports. |
| `emp1@company.com` | `ROLE_EMPLOYEE` | — | **Employee Self-Service**: Personal profile viewing, daily clock-in/out, leave application, personal quota tracking, account password rotation, personal session oversight. Strictly forbidden from administrative management views. |

---

## 3. Core Architectural Safeguards & Hardening

### 3.1 SUPER_ADMIN Zero-Write Boundary (Section 9.4)
- **Security Filter Chain**: `SecurityConfig.java` restricts mutation paths (`POST /employees/**`, `POST /departments/**`, `POST /attendance/correct`, `POST /leaves/*/approve`, `POST /holidays/**`) strictly to `HR_ADMIN`.
- **Method-Level Security**: Controllers enforce `@PreAuthorize("hasRole('HR_ADMIN')")`.
- **UI Guard**: Thymeleaf templates use `sec:authorize="hasRole('HR_ADMIN')"` so zero write controls or mutation forms are rendered into the DOM for Super Admin.
- **Verification**: Verified by automated test suite `RoleBoundarySecurityTest`.

### 3.2 Append-Only Immutable Audit Trail
- **PostgreSQL Trigger**: `trg_audit_log_immutable` is installed on table `audit_log`, executing `RAISE EXCEPTION` on any `UPDATE` or `DELETE` statement.
- **AOP Interception**: Methods annotated with `@Audited` automatically capture:
  - Timestamp (`occurred_at`)
  - Authenticated user ID & HR Code (`hr_code`)
  - Target entity type and UUID
  - Before and After state snapshots in PostgreSQL `jsonb`
  - Spring Session ID and client IP address

### 3.3 Concurrency & Race-Condition Safe Leave Engine
- **Pessimistic Locking**: `LeaveBalanceRepository.findForUpdate()` executes `SELECT ... FOR UPDATE` ensuring quota deductions cannot race under concurrent requests.
- **Overlap Prevention**: Enforces `existsOverlappingLeave()` preventing double-booking the same calendar day.
- **Provisional Hold-Then-Convert**: Balances reserve quota during application in ascending calendar year order, preventing deadlocks and overdrawing under multi-threaded bursts.
- **Attendance Auto-Sync**: Approved leave dates automatically insert/update corresponding daily records in `attendance_records` with status `ON_LEAVE`.
- **Verification**: Verified by `LeaveConcurrencyTest` (multithreaded overlapping date checks and quota exhaustion).

### 3.4 Atomic Employee Code Sequencing
- PostgreSQL sequences `seq_employee_code` and `seq_hr_code` guarantee gapless, collision-free codes (`EMP-001`, `HR-001`) even under high concurrency.
- **Verification**: Verified by `EmployeeCodeConcurrencyTest`.

---

## 4. Endpoints & Route Directory

### Health & Monitoring
- `GET /actuator/health` — Spring Boot Actuator liveness and readiness probe (public)
- `GET /actuator/info` — Actuator application information (public)
- `GET /api/health` — Lightweight deployment status check (public backward compatibility)

### Authentication & Account
- `GET /login` — Authentication page with pre-filled test persona hints
- `POST /logout` — Invalidate session and record session termination
- `GET /account/password` — Authenticated password rotation page
- `POST /account/password` — Change current user password (audited)
- `GET /dashboard` — Role-differentiated dashboard:
  - **Super Admin & HR Admin**: Headline KPIs, Department Headcount Doughnut Chart, 14-Day Attendance Trends Stacked Bar Chart, Cumulative Leave Usage Doughnut Chart, Recent Audit Trail.
  - **Employee**: Punch clock status, quick leave balance summary, personal profile.
- `GET /sessions` — Live active session viewer (Spring Session JDBC)
- `POST /sessions/{id}/revoke` — Revoke live session (self-service for Employee/HR Admin, global for Super Admin)

### Visual Analytics Endpoints (Milestone 4A)
- `GET /api/analytics/headcount-by-dept` — JSON distribution of active headcount by department
- `GET /api/analytics/attendance-trends?days=14` — 14-day rolling continuous status counts
- `GET /api/analytics/leave-usage` — Cumulative approved leave days grouped by leave category

### Tabular Reporting & RFC-4180 Streaming CSV Exports (Milestone 4B & GAP-07)
- `GET /reports/employees/csv` — Full employee directory export (StreamingResponseBody)
- `GET /reports/attendance/csv?date=YYYY-MM-DD&departmentId=...` — Daily roster export
- `GET /reports/audit/csv?action=...&fromDate=...&toDate=...` — Filtered immutable audit log export with CSV formula injection defense

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
- `POST /attendance/clock-in` — Record daily check-in with work mode (`ON_SITE`, `REMOTE`)
- `POST /attendance/clock-out` — Record daily check-out and compute worked duration
- `GET /attendance/manage` — Daily attendance roster oversight
- `POST /attendance/correct` — Audited manual adjustment (`HR_ADMIN` only)

### Leave Management & Company Holidays
- `GET /leaves/my` — Self-service leave application form & balance summary
- `POST /leaves/apply` — Submit leave request with quota validation
- `GET /leaves/manage` — Pending approval queue & organization leave history
- `POST /leaves/{id}/approve` — Approve request & auto-sync attendance (`HR_ADMIN` only)
- `POST /leaves/{id}/reject` — Reject request with documented reason (`HR_ADMIN` only)
- `GET /holidays` — Company holiday calendar (publicly visible to all authenticated roles)
- `POST /holidays/new` — Create company holiday or recurring observance (`HR_ADMIN` only)
- `POST /holidays/{id}/delete` — Remove holiday record (`HR_ADMIN` only)

### Audit Oversight
- `GET /audit` — Paginated and date-filtered audit log viewer (`SUPER_ADMIN` and `HR_ADMIN`)

---

## 5. Operations, Maintenance & Database Runbook

### 5.1 Docker Compose Deployment
```bash
# Start containerized PostgreSQL 16 and Spring Boot app
docker compose up -d

# Verify container health
docker compose ps

# Tail logs
docker compose logs -f app
```

### 5.2 Build & Test Execution
```bash
# Compile and package application
./mvnw clean package -DskipTests=false

# Run complete test suite (95 tests: Security, Concurrency, Performance, Integration)
./mvnw test

# Start the Spring Boot Application locally
./mvnw spring-boot:run
```

### 5.3 Database Migrations (Flyway)
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

### 5.4 Backup & Disaster Recovery
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
| **Milestone 3A** | Workday punch engine, Nightly finalizer cron (23:59 Mon-Fri), Audited manual correction, Attendance views | Verified |
| **Milestone 3B** | Annual leave quotas, Pessimistic lock hold-then-convert, Attendance auto-sync, Concurrency suite | Verified |
| **Milestone 4A** | Analytics engine, Live Chart.js Visual Endpoints (Headcount, 14-Day Attendance, Leave Usage) | Delivered |
| **Milestone 4B** | RFC-4180 Streaming CSV Export engine (Employees, Attendance roster, Audited log), Download triggers | Delivered |
| **Milestone 4C** | System Hardening & Role Boundary Verification Suite (`RoleBoundarySecurityTest`) | Delivered |
| **Milestone 4D** | System Architecture & Operations Handover Runbook (`HANDOVER_RUNBOOK.md`) | Delivered |
| **Milestone 5A** | Container hardening (non-root `appuser`, JVM memory ergonomics, curl probes) & Docker Compose stack | Delivered |
| **Milestone 5B** | Automated CI/CD GitHub Actions pipeline (`ci.yml`) for JDK 25 and PostgreSQL 16 | Delivered |
| **Milestone 5C** | True multithreaded quota exhaustion concurrency tests & full-stack integration regression suite (95/95 passing) | Delivered |
