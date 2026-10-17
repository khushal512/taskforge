# TaskForge

TaskForge is a learning project for building a background job platform in small, tested phases. Phase 1 added a Spring Boot HTTP service and health route. Phase 2 added pure-Java job lifecycle rules. Phase 3 added PostgreSQL persistence. Phase 4 added job submission and lookup APIs. Phase 5 added a database-backed worker. Phase 6 makes its queued-job claim safe across competing worker instances.

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

The response is `{"status":"UP"}`. This route only confirms that the HTTP application responds; it does not check a database or a worker. The database connection and Flyway migrations are checked during application startup.

## Job API

Submit a report job from PowerShell while the app is running:

```powershell
$job = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/jobs -ContentType 'application/json' -Body '{"jobType":"GENERATE_REPORT","payload":{"reportName":"Quarterly report"}}'
$job.id
Invoke-RestMethod "http://localhost:8080/api/v1/jobs/$($job.id)"
```

`POST /api/v1/jobs` returns `201 Created`, a `Location` header pointing to `/api/v1/jobs/{id}`, and the saved job. `GET /api/v1/jobs/{id}` returns that job with `200 OK`. The response includes its ID, job type, normalized payload, status, attempt count, creation and update times, and an optional failure reason. New jobs have status `QUEUED` and attempt count `0`; the worker may change the status soon after the response. Poll GET to observe it. A completed simulated job has attempt count `1`.

Only `jobType` and `payload.reportName` are accepted in a submission. The server generates the ID, status, attempt count, and timestamps. The only supported type is `GENERATE_REPORT`; report names must be nonblank, at most 100 characters, and one line. Unknown JSON fields are rejected. Invalid input or a malformed UUID returns `400`; an unknown valid UUID returns `404`. API errors use `code`, `message`, and `fieldErrors` fields without stack traces or database details. There is no list or retry endpoint.

## Database-backed worker

One scheduled worker cycle selects the oldest available queued job with PostgreSQL `FOR UPDATE SKIP LOCKED`, applies the domain `start()` transition, and commits `RUNNING`. It then runs a registered handler outside a transaction and saves either `SUCCEEDED` or `FAILED` in a second transaction. A competing worker skips a row locked by an uncommitted claim and may take another queued job. The only handler builds a deterministic report **preview in memory**. No report file, download, or external service exists; the preview is not stored. `SUCCEEDED` means that this simulation completed, not that a deliverable report was generated. Handler errors produce a fixed safe failure reason rather than exposing exception text.

The worker checks one job per tick. `WORKER_POLL_DELAY_MS` defaults to `1000` milliseconds between completed ticks. Set `WORKER_SCHEDULING_ENABLED=false` to disable automatic polling, for example while inspecting queued jobs locally. These settings affect only the worker; the POST endpoint still saves jobs without executing handlers in the request thread.

Multiple TaskForge worker instances can claim **different** queued jobs through this PostgreSQL claim path. Skipping a locked older job means processing order is not strictly first-in-first-out. If a process stops after committing `RUNNING`, that job remains `RUNNING`; there is no retry or crash recovery yet. If saving the final status fails, the same limitation applies. This version does not guarantee exactly-once execution or effects. See [ADR 0004](docs/adr/0004-concurrent-claiming.md).

The default HTTP port is 8080. To select another port for the current PowerShell session, set `$env:SERVER_PORT = '8081'` before starting the app. `DATABASE_URL` defaults to `jdbc:postgresql://localhost:5432/taskforge` and `DATABASE_USERNAME` defaults to `taskforge`. If you change the Compose database name or host port, adjust `DATABASE_URL` too. The password has no usable default: supply `DATABASE_PASSWORD` in the shell. `.env.example` lists configuration names but contains no credential. Compose can read an ignored `.env` file; Spring Boot reads the shell environment, not that file. Never commit a real `.env` file.

`mvn verify` starts an isolated PostgreSQL Testcontainers instance, so Docker must be running even if the Compose database is stopped. Stop the local database with `docker compose down` when finished. The named volume retains its data; deleting it would remove local jobs.

The PostgreSQL JDBC driver sends the JVM timezone name when connecting. This Windows JDK reports `Asia/Calcutta`, which PostgreSQL 17.11 rejects. Maven configures UTC for test and `spring-boot:run` JVMs. If you run the packaged jar directly, use `java -Duser.timezone=UTC -jar target/taskforge-0.1.0-SNAPSHOT.jar`. This changes only that Java process and does not change Windows settings or how instants are stored.

On some Codex terminals, Java may report `user.home=C:\` even when `USERPROFILE` points to the Windows account. If Maven reports an inaccessible `C:\.m2\repository`, set `$env:MAVEN_OPTS = "-Duser.home=$env:USERPROFILE"` for that terminal session, then rerun Maven. This does not change machine-wide configuration.

## Current architecture

```text
HTTP client → JobController → JobApplicationService → domain Job → JpaJobStore → PostgreSQL
WorkerScheduler → JobWorker → JpaJobStore → PostgreSQL
                          → JobHandlerRegistry → ReportJobHandler
HTTP client → HealthController → HealthResponse JSON
```

`com.taskforge` is the application root. The `api` package contains HTTP DTOs, controllers, and safe error mapping. The `application` package coordinates use cases and supplies a testable clock. The `domain` package contains pure-Java job lifecycle rules. The `persistence` package maps a domain `Job` to a JPA row. The `worker` package schedules cycles and runs allowlisted handlers. Flyway creates the schema and Hibernate validates it. PostgreSQL is the source of truth for job state. Redis will be evaluated only after the database-backed worker is understood and tested.

The domain model and legal transitions are documented in [ADR 0001](docs/adr/0001-job-domain.md). The persistence design and limits are documented in [ADR 0002](docs/adr/0002-postgres-persistence.md). The first worker design is documented in [ADR 0003](docs/adr/0003-first-worker.md); its concurrent claim is documented in [ADR 0004](docs/adr/0004-concurrent-claiming.md).

## Development sequence

1. Runnable service and health check (Phase 1)
2. Job domain and state machine (Phase 2)
3. PostgreSQL persistence (Phase 3)
4. Submission and status APIs (Phase 4)
5. First background worker (Phase 5)
6. Multiple workers and concurrency control (Phase 6)
7. Retries and failure handling (planned Phase 7)
8. Redis integration, if it adds a justified benefit (Phase 8)
9. Idempotency and crash recovery (Phase 9)
10. Authentication and authorization (Phase 10)
11. Expanded tests and quality checks (Phase 11)
12. Logs, health, and metrics (Phase 12)
13. Docker and CI (Phase 13)
14. Architecture and portfolio documentation (Phase 14)

Each phase is reviewed and tested before the next begins. See the Git history for the implementation steps.
