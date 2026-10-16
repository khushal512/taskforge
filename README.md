# TaskForge

TaskForge is a learning project for building a background job platform in small, tested phases. Phase 1 contains a Spring Boot HTTP service and a basic health route. It does not process or store jobs yet.

## Requirements

- Java 21 JDK
- Maven 3.9 or newer

Docker is not needed for Phase 1. PostgreSQL and Docker Compose will be introduced when persistence is added.

## Run locally

In PowerShell, from the repository root:

```powershell
java -version
mvn verify
mvn spring-boot:run
```

In another terminal:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

The response is `{"status":"UP"}`. This route only confirms that the HTTP application responds; it does not check a database or a worker.

The default port is 8080. To select another port for the current PowerShell session, set `$env:SERVER_PORT = '8081'` before starting the app. `.env.example` documents the variable; Spring Boot does not automatically load a `.env` file. Keep real local `.env` files out of Git.

## Current architecture

```text
HTTP client → HealthController → HealthResponse JSON
```

`com.taskforge` is the application root. Future packages will separate API, application services, domain rules, persistence, workers, and configuration. PostgreSQL will be the source of truth for job state. The first worker will poll the database; Redis will be evaluated only after that design is working and tested.

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
