# Enterprise HRMS

Enterprise Human Resource Management System with multi-tier access control, immutable audit trails, attendance tracking, and leave management.

## Tech Stack
- **Runtime**: Java 25
- **Framework**: Spring Boot 4.1.0
- **Database**: PostgreSQL 16+ (Spring Data JPA + Flyway Migrations)
- **Security & Sessions**: Spring Security 6 + Spring Session JDBC
- **Audit Subsystem**: Declarative Spring AOP (`@Audited`) + Append-Only PostgreSQL Trigger
- **Frontend**: Thymeleaf 3.1 + Tailwind CSS + Chart.js
- **DevOps & Containers**: Hardened multi-stage Dockerfile (non-root runtime), Docker Compose, Actuator Probes, GitHub Actions CI

## Documentation & Handover
For the complete technical architecture, security role matrix, API directory, database migration runbook, and verification checklist, please consult:
👉 **[HANDOVER_RUNBOOK.md](HANDOVER_RUNBOOK.md)**

## Quick Start

### 1. Run with Docker Compose
```bash
# Start PostgreSQL 16 and Spring Boot application stack
docker compose up -d

# Check service health
docker compose ps

# View application logs
docker compose logs -f app
```
The application will be accessible at: `http://localhost:8080` (health probes at `http://localhost:8080/actuator/health`).

### 2. Run Locally with Maven
```bash
# Run all automated tests (Concurrency, Security boundaries, Business logic)
./mvnw test

# Launch application on http://localhost:8080
./mvnw spring-boot:run
```

### Seed Accounts (Password configured via `.env` / `HRMS_DEFAULT_USER_PASSWORD`)
- `superadmin@company.com` — **Super Admin**: System oversight, live session revocation, visual analytics, audit logs.
- `hr1@company.com` — **HR Admin**: Employee directory, bulk CSV import, attendance corrections, leave approvals, reports, company holidays.
- `emp1@company.com` — **Employee**: Self-service profile, punch clock, leave application, account password rotation.
