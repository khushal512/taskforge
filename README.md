# TaskForge

TaskForge is a learning project for building a background job platform in small, tested phases. Phase 1 added a Spring Boot HTTP service and health route. Phase 2 added pure-Java job lifecycle rules. Phase 3 stores jobs in PostgreSQL. There are no job HTTP endpoints or workers yet.

## Requirements

- Java 21 JDK
- Maven 3.9 or newer
- Docker Desktop with Docker Engine running (for PostgreSQL and integration tests)

PostgreSQL runs through Docker Compose for local development; a Windows PostgreSQL installation is not needed.

## Run locally

In PowerShell, from the repository root:

```powershell
java -version
$env:DATABASE_PASSWORD = 'choose-a-local-password'
docker compose up -d --wait
mvn verify
mvn spring-boot:run
```

In another terminal:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

The response is `{"status":"UP"}`. This route only confirms that the HTTP application responds; it does not check a database or a worker. The database connection and Flyway migration are checked during application startup.

The default HTTP port is 8080. To select another port for the current PowerShell session, set `$env:SERVER_PORT = '8081'` before starting the app. `DATABASE_URL` defaults to `jdbc:postgresql://localhost:5432/taskforge` and `DATABASE_USERNAME` defaults to `taskforge`. If you change the Compose database name or host port, adjust `DATABASE_URL` too. The password has no usable default: supply `DATABASE_PASSWORD` in the shell. `.env.example` lists configuration names but contains no credential. Compose can read an ignored `.env` file; Spring Boot reads the shell environment, not that file. Never commit a real `.env` file.

`mvn verify` starts an isolated PostgreSQL Testcontainers instance, so Docker must be running even if the Compose database is stopped. Stop the local database with `docker compose down` when finished. The named volume retains its data; deleting it would remove local jobs.

The PostgreSQL JDBC driver sends the JVM timezone name when connecting. This Windows JDK reports `Asia/Calcutta`, which PostgreSQL 17.11 rejects. Maven configures UTC for test and `spring-boot:run` JVMs. If you run the packaged jar directly, use `java -Duser.timezone=UTC -jar target/taskforge-0.1.0-SNAPSHOT.jar`. This changes only that Java process and does not change Windows settings or how instants are stored.

On some Codex terminals, Java may report `user.home=C:\` even when `USERPROFILE` points to the Windows account. If Maven reports an inaccessible `C:\.m2\repository`, set `$env:MAVEN_OPTS = "-Duser.home=$env:USERPROFILE"` for that terminal session, then rerun Maven. This does not change machine-wide configuration.

## Current architecture

```text
HTTP client → HealthController → HealthResponse JSON
```

`com.taskforge` is the application root. The `domain` package contains pure-Java job lifecycle rules. The `persistence` package maps a domain `Job` to a JPA row. Flyway creates the schema and Hibernate validates it. PostgreSQL is the source of truth for job state. Future packages will add application services and workers; Redis will be evaluated only after a database-backed worker is working and tested.

The domain model and legal transitions are documented in [ADR 0001](docs/adr/0001-job-domain.md). The persistence design and limits are documented in [ADR 0002](docs/adr/0002-postgres-persistence.md).

## Development sequence

1. Runnable service and health check (Phase 1)
2. Job domain and state machine (Phase 2)
3. PostgreSQL persistence (Phase 3)
4. Submission and status APIs (Phase 4)
5. First background worker (Phase 5)
6. Retries and failure handling (Phase 6)
7. Multiple workers and concurrency control (Phase 7)
8. Redis integration, if it adds a justified benefit (Phase 8)
9. Idempotency and crash recovery (Phase 9)
10. Authentication and authorization (Phase 10)
11. Expanded tests and quality checks (Phase 11)
12. Logs, health, and metrics (Phase 12)
13. Docker and CI (Phase 13)
14. Architecture and portfolio documentation (Phase 14)

Each phase is reviewed and tested before the next begins. See the Git history for the implementation steps.
