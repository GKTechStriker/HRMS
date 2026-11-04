# Enterprise HRMS

Enterprise Human Resource Management System built for Build with Bharat 4.0 Hackathon.

## Tech Stack
- **Runtime**: Java 25
- **Framework**: Spring Boot 4.1.0
- **Database**: PostgreSQL 16+ (Spring Data JPA + Flyway Migrations)
- **Security & Sessions**: Spring Security 6 + Spring Session JDBC
- **Audit Subsystem**: Declarative Spring AOP (`@Audited`) + Append-Only PostgreSQL Trigger
- **Frontend**: Thymeleaf 3.1 + Tailwind CSS + Chart.js

## Documentation & Handover
For the complete technical architecture, security role matrix, API directory, database migration runbook, and verification checklist, please consult:
👉 **[HANDOVER_RUNBOOK.md](file:///home/techstriker/HRMS/HANDOVER_RUNBOOK.md)**

## Quick Start
```bash
# Run all automated tests (Concurrency, Security boundaries, Business logic)
./mvnw test

# Launch application on http://localhost:8080
./mvnw spring-boot:run
```

### Seed Accounts (Password: `Password@123`)
- `admin@company.com` — **Super Admin**: System oversight, live session revocation, visual analytics.
- `hr1@company.com` — **HR Admin**: Employee directory, bulk CSV import, attendance corrections, leave approvals, reports.
- `emp1@company.com` — **Employee**: Self-service profile, punch clock, leave application.
