# Spring Boot Starter Classes — Installation Instructions

This folder contains all the initial Spring Boot classes you need to get started.

## Files Included

```
spring-boot-starter-files/
├── java/
│   ├── HrmsApplication.java              # Main Spring Boot entry point
│   ├── HealthController.java             # GET /api/health endpoint
│   ├── SecurityConfig.java               # Spring Security & CORS config
│   ├── ResourceNotFoundException.java     # Custom exception (404)
│   ├── BusinessException.java            # Custom exception (400)
│   └── GlobalExceptionHandler.java       # Centralized exception handling
├── resources/
│   ├── application.yml                   # Spring Boot configuration
│   └── db/migration/
│       └── V1__initial_schema.sql        # Flyway baseline migration
└── INSTALLATION_INSTRUCTIONS.md          # This file
```

## How to Use

### Step 1: Verify Your Project Structure

After cloning your GitHub repo and creating the directory structure, you should have:

```
hrms-hackathon/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/hrms/
│   │   │       ├── controller/
│   │   │       ├── config/
│   │   │       ├── exception/
│   │   │       ├── entity/
│   │   │       ├── repository/
│   │   │       └── service/
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/
│   │           └── migration/
│   └── test/
│       └── java/
│           └── com/hrms/
├── pom.xml
├── Dockerfile
└── .git/
```

### Step 2: Copy Java Files

Copy the Java classes from `java/` to your project:

```bash
# From your project root:
cp spring-boot-starter-files/java/HrmsApplication.java \
   src/main/java/com/hrms/

cp spring-boot-starter-files/java/HealthController.java \
   src/main/java/com/hrms/controller/

cp spring-boot-starter-files/java/SecurityConfig.java \
   src/main/java/com/hrms/config/

cp spring-boot-starter-files/java/ResourceNotFoundException.java \
   src/main/java/com/hrms/exception/

cp spring-boot-starter-files/java/BusinessException.java \
   src/main/java/com/hrms/exception/

cp spring-boot-starter-files/java/GlobalExceptionHandler.java \
   src/main/java/com/hrms/exception/
```

Or, if you have a package structure like `src/main/java/com/hrms/`, just copy files to the right locations.

### Step 3: Copy Configuration Files

Copy the configuration files to your project:

```bash
# Copy application.yml
cp spring-boot-starter-files/resources/application.yml \
   src/main/resources/

# Copy Flyway migration
cp spring-boot-starter-files/resources/db/migration/V1__initial_schema.sql \
   src/main/resources/db/migration/
```

### Step 4: Update application.yml

Edit `src/main/resources/application.yml` and update the database connection:

```yaml
datasource:
  url: jdbc:postgresql://YOUR_HOST:5432/YOUR_DB
  username: YOUR_USERNAME
  password: YOUR_PASSWORD
```

**For managed Postgres (Render/Railway):**
```yaml
datasource:
  url: jdbc:postgresql://dpg-xyz.c99.com:5432/hrms_db
  username: user
  password: password
```

**For local Postgres:**
```bash
# First, create the database locally
createdb hrms_dev

# Then in application.yml:
datasource:
  url: jdbc:postgresql://localhost:5432/hrms_dev
  username: postgres
  password: postgres
```

### Step 5: Verify Build

```bash
# From project root:
mvn clean package

# Expected output:
# BUILD SUCCESS
```

If the build fails:
- Verify Java 25 is installed: `java -version`
- Verify Maven is installed: `mvn -version`
- Check that pom.xml has all dependencies
- Verify no typos in application.yml

### Step 6: Run the Application

```bash
mvn spring-boot:run
```

Expected output:
```
Started HrmsApplication in X.XXX seconds
```

### Step 7: Test the Health Endpoint

In another terminal:

```bash
curl http://localhost:8080/api/health
```

Expected response:
```json
{
  "message": "HRMS is running",
  "status": "OK",
  "timestamp": "2026-09-22T10:30:00"
}
```

### Step 8: Commit to Git

```bash
git add .
git commit -m "feat: initial Spring Boot setup with health endpoint and exception handling"
git push origin develop
```

---

## What Each File Does

### HrmsApplication.java
- **Purpose**: Main entry point for the Spring Boot application
- **What it does**: Starts the entire application with `SpringApplication.run()`
- **When to edit**: Only if you need to customize startup behavior
- **Location**: `src/main/java/com/hrms/HrmsApplication.java`

### HealthController.java
- **Purpose**: Provides a health check endpoint for monitoring
- **Endpoint**: `GET /api/health`
- **Returns**: JSON with message, status, and timestamp
- **When to use**: Used by deployment systems (Render, Railway) for health checks
- **Location**: `src/main/java/com/hrms/controller/HealthController.java`

### SecurityConfig.java
- **Purpose**: Configures Spring Security and CORS
- **Features**:
  - BCrypt password encoding for secure password storage
  - CORS configuration for cross-origin requests
- **When to edit**: Add more security rules or auth config in future phases
- **Location**: `src/main/java/com/hrms/config/SecurityConfig.java`

### ResourceNotFoundException.java
- **Purpose**: Custom exception for "resource not found" scenarios
- **When to throw**: When an Employee, Department, or other resource doesn't exist
- **HTTP Status**: 404
- **Example**: `throw ResourceNotFoundException.forEntity("Employee", "123")`
- **Location**: `src/main/java/com/hrms/exception/ResourceNotFoundException.java`

### BusinessException.java
- **Purpose**: Custom exception for business logic violations
- **When to throw**: 
  - Insufficient leave balance
  - Duplicate employee codes
  - Invalid state transitions
  - Data validation failures
- **HTTP Status**: 400
- **Example**: `throw new BusinessException("Only 5 days left this year")`
- **Location**: `src/main/java/com/hrms/exception/BusinessException.java`

### GlobalExceptionHandler.java
- **Purpose**: Centralized exception handling for all controllers
- **What it does**:
  - Catches all exceptions thrown by services/controllers
  - Converts them into standardized JSON error responses
  - Returns appropriate HTTP status codes (400, 404, 500)
- **When to edit**: Add more exception handlers for new exception types
- **Location**: `src/main/java/com/hrms/exception/GlobalExceptionHandler.java`

### application.yml
- **Purpose**: Spring Boot configuration file
- **Contains**:
  - Database connection details
  - JPA/Hibernate settings
  - Flyway migration settings
  - Logging configuration
  - Server settings (port 8080)
- **When to edit**: When changing database, adding new features, adjusting logging
- **Location**: `src/main/resources/application.yml`

### V1__initial_schema.sql
- **Purpose**: Flyway baseline migration file
- **Naming convention**: V1 = version 1, __ = separator, initial_schema = description
- **Format**: `V{NUMBER}__{DESCRIPTION}.sql`
- **When to edit**: Add initial table creation statements
- **Next migrations**: V2__add_attendance.sql, V3__add_leave.sql, etc.
- **Location**: `src/main/resources/db/migration/V1__initial_schema.sql`

---

## Key Points for the Hackathon

✅ **Health check endpoint is working** — Used by deployment platforms (Render, Railway)

✅ **Exception handling is centralized** — All errors return consistent JSON format

✅ **CORS is configured** — Frontend can make requests to this API

✅ **Password encoding is set up** — Ready for user authentication in Person B's work

✅ **Database migrations are set up** — Flyway will manage schema automatically

✅ **Logging is configured** — You can see what's happening during development

---

## Troubleshooting

### Build fails with "Java version not supported"
```
mvn clean package
# Error: Source option must be explicitly set when using javac 25.x
```
**Fix**: Verify pom.xml has Java 25 compiler settings:
```xml
<properties>
  <java.version>25</java.version>
  <maven.compiler.source>25</maven.compiler.source>
  <maven.compiler.target>25</maven.compiler.target>
</properties>
```

### "Flyway migration failed"
```
ERROR: [V1__initial_schema.sql] failed
```
**Fix**: Verify:
1. `V1__initial_schema.sql` exists in `src/main/resources/db/migration/`
2. PostgreSQL is running and accessible
3. Database exists: `createdb hrms_dev`
4. Connection details in `application.yml` are correct

### "Connection refused" error
```
ERROR: Connection to localhost:5432 refused
```
**Fix**:
- Local PostgreSQL: `psql -U postgres` should work
- Remote database: Check connection string in `application.yml`
- Test connection: `psql "postgresql://user:pass@host:5432/dbname"`

### Port 8080 already in use
```
ERROR: Address already in use: bind
```
**Fix**: Change port in `application.yml`:
```yaml
server:
  port: 8081  # Change to a different port
```

---

## Next Steps (Day 4 Onwards)

Once this is working:

1. **Day 2**: Add tables to `V2__add_user_tables.sql` for authentication
2. **Day 4**: Create Employee entity and repository
3. **Day 5**: Create EmployeeService with CRUD logic
4. **Day 6**: Create EmployeeController with REST endpoints

See the 20-day hackathon schedule for detailed tasks.

---

## Quick Reference

| Command | Purpose |
|---------|---------|
| `mvn clean package` | Build the application |
| `mvn spring-boot:run` | Run locally on port 8080 |
| `mvn test` | Run unit tests |
| `curl http://localhost:8080/api/health` | Test health endpoint |
| `createdb hrms_dev` | Create local PostgreSQL database |
| `psql -U postgres hrms_dev` | Connect to local database |

---

## You're Ready!

Once all files are in place and the health endpoint works, commit everything and start Day 1 of the 20-day schedule.

Good luck! 🚀
