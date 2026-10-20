# TaskForge

TaskForge is a learning project for building a background job platform in small, tested phases. Phase 1 added a Spring Boot HTTP service and health route. Phase 2 added pure-Java job lifecycle rules. Phase 3 added PostgreSQL persistence. Phase 4 added job submission and lookup APIs. Phase 5 added a database-backed worker. Phase 6 made claims safe across competing workers. Phase 7 adds bounded retries, leases, and crash recovery.

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

`POST /api/v1/jobs` without an `Idempotency-Key` header, or with a new key, returns `201 Created`, a `Location` header pointing to `/api/v1/jobs/{id}`, and the saved job. `GET /api/v1/jobs/{id}` returns that job with `200 OK`. The response includes its ID, job type, normalized payload, status, attempt count, creation and update times, and an optional terminal failure reason. New jobs have status `QUEUED` and attempt count `0`; the worker may change the status soon after the response. Poll GET to observe it. A retrying job can show `RETRY_WAIT`; `attemptCount` is the number of attempts **started**, not the number of retries scheduled.

The optional `Idempotency-Key` is trimmed and must then contain 1–128 ASCII letters, digits, `.`, `_`, `~`, or `-`. A supplied invalid key returns `400 Bad Request`; it is never treated as absent. Repeating the same key with the same normalized job type and report name returns `200 OK`, the existing job's **current** representation, and its original `Location`. Reusing that key with a different logical request returns `409 Conflict`. A replay keeps the original job ID but does not preserve the first response snapshot: the first response may show `QUEUED` and a later replay may show `SUCCEEDED`.

Keys are globally scoped because this API has no authentication or client identity. They are retained permanently in this version, with no expiry or cleanup policy. This prevents duplicate job creation for a retained key; it does not guarantee exactly-once handler execution or external effects. During rollout, stop older application instances from accepting submissions before relying on this guarantee, since they do not interpret the header. The API is intended for local development, not public deployment without authentication and job ownership checks.

Only `jobType` and `payload.reportName` are accepted in a submission. The server generates the ID, status, attempt count, and timestamps. The only supported type is `GENERATE_REPORT`; report names must be nonblank, at most 100 characters, and one line. Unknown JSON fields are rejected. Invalid input or a malformed UUID returns `400`; an unknown valid UUID returns `404`. API errors use `code`, `message`, and `fieldErrors` fields without stack traces or database details. There is no list or retry endpoint.

### Attempt history

Inspect a job's execution attempts after submitting it:

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/jobs/$($job.id)/attempts"
```

`GET /api/v1/jobs/{id}/attempts` returns a JSON array in `attemptNumber` order. `startedAt` is the recorded or reconstructed start timestamp; `finishedAt` is when that attempt closed, or `null` while it is running. `outcome` describes that attempt: `RUNNING` is open, `RETRY_WAIT` means it ended and a retry was scheduled, and `SUCCEEDED` or `FAILED` records its final result. `failureReason` is a safe display reason for an attempt that failed or scheduled a retry; unfamiliar stored reasons are shown as `Job attempt failed`. `nextEligibleAt` is the time when that attempt's scheduled retry becomes eligible. Inapplicable `finishedAt`, `failureReason`, and `nextEligibleAt` fields are returned as JSON `null`.

The history keeps each attempt's outcome. An earlier attempt can therefore show `RETRY_WAIT` even when a later attempt succeeds. `GET /api/v1/jobs/{id}` reports the separate, current job status, such as `SUCCEEDED`.

`startTimeSource` explains where `startedAt` came from: `RECORDED` means it was saved when the attempt started; `LEGACY_DERIVED` means it was inferred from a pre-Phase-7 `RUNNING` job's last update time; `LEGACY_SYNTHETIC` means a pre-Phase-7 terminal job's creation time was used for reconstruction. The legacy values are not observed start times.

An existing job with no started attempts returns `200 OK` with `[]`. An unknown valid UUID returns the existing safe `404` response; a malformed UUID returns `400`. This endpoint is currently unauthenticated and intended for local development only. Do not expose it publicly until authentication and job ownership checks are implemented.

## Database-backed worker

One scheduled worker cycle first checks one expired `RUNNING` job for recovery, then claims an eligible `QUEUED` or `RETRY_WAIT` job with PostgreSQL `FOR UPDATE SKIP LOCKED`. The claim transaction applies `start()`, records the numbered attempt, sets a lease, and commits `RUNNING` before the handler executes. A separate heartbeat thread can renew the lease during a slow handler. Completion and recovery use short, guarded transactions that lock the job and update its exact attempt record together. The handler runs outside database transactions. A competing worker skips a locked row and may take another job.

Only an explicitly retryable handler error or an expired lease schedules another attempt. Other handler errors fail permanently with a fixed safe reason. The default maximum is three started attempts. After failed attempt 1, the job waits 5 seconds; after attempt 2, 10 seconds; after attempt 3 it becomes `FAILED`. The delay doubles up to a 5-minute cap. PostgreSQL stores the next eligible time; no thread or transaction waits through backoff. The only production handler builds a deterministic report **preview in memory**. No report file, download, or external service exists; the preview is not stored. `SUCCEEDED` means this simulation completed, not that a deliverable report was generated.

The worker checks at most one abandoned job and processes at most one eligible job per tick. `WORKER_POLL_DELAY_MS` defaults to `1000` milliseconds between completed ticks. Set `WORKER_SCHEDULING_ENABLED=false` to disable automatic polling, for example while inspecting queued jobs locally. `WORKER_MAX_ATTEMPTS`, `WORKER_RETRY_BASE_DELAY_MS`, `WORKER_RETRY_DELAY_CAP_MS`, `WORKER_LEASE_DURATION_MS`, and `WORKER_HEARTBEAT_INTERVAL_MS` have safe defaults shown in `.env.example`. Invalid limits prevent startup. The maximum is stored per submitted job; changing it later affects only new jobs. The POST endpoint still saves jobs without executing handlers in the request thread.

The built-in fixed-delay scheduler runs one handler at a time per application instance. Its single heartbeat thread therefore serves one scheduled claim at a time. If an instance later runs handlers concurrently, use a bounded heartbeat executor and test that a blocked renewal cannot delay another claim past its lease.

Multiple TaskForge instances can claim different jobs. Skipping a locked older row means processing order is not strictly first-in-first-out. If a worker crashes after claiming, its lease eventually expires and another worker can schedule a retry. An old worker cannot renew or save a result after its lease expires or a newer attempt starts. This fences **PostgreSQL state**, not execution or external effects: an old handler may still run, and a successful handler can be repeated if its final save fails. This version does not guarantee exactly-once execution or effects. The report preview has no external side effect. See [ADR 0005](docs/adr/0005-retries-leases-recovery.md).

**Upgrade from Phase 6:** Stop and confirm all Phase 6 application instances have exited before applying Flyway V3 or starting Phase 7 instances. Phase 6 workers can write terminal state without a lease or attempt-history check; mixed versions cannot safely share this schema. V3 marks any existing `RUNNING` job's lease expired so the new worker can recover it. Historical terminal attempts did not record an actual start time; their backfilled start is explicitly marked synthetic.

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

The domain model and legal transitions are documented in [ADR 0001](docs/adr/0001-job-domain.md). The persistence design and limits are documented in [ADR 0002](docs/adr/0002-postgres-persistence.md). The first worker design is documented in [ADR 0003](docs/adr/0003-first-worker.md); its concurrent claim is documented in [ADR 0004](docs/adr/0004-concurrent-claiming.md). [ADR 0005](docs/adr/0005-retries-leases-recovery.md) records the revised Phase 7 retry and ownership rules; earlier ADRs describe the design at their respective phases.

## Development sequence

1. Runnable service and health check (Phase 1)
2. Job domain and state machine (Phase 2)
3. PostgreSQL persistence (Phase 3)
4. Submission and status APIs (Phase 4)
5. First background worker (Phase 5)
6. Multiple workers and concurrency control (Phase 6)
7. Retries, leases, and crash recovery (Phase 7)
8. Job execution history API (Phase 8)
9. Idempotent job submission (Phase 9)
10. CI verification baseline (Phase 10)
11. Expanded tests and quality checks (Phase 11)
12. Logs, health, and metrics (Phase 12)
13. Docker packaging and deployment workflow (Phase 13)
14. Architecture and portfolio documentation (Phase 14)

Each phase is reviewed and tested before the next begins. See the Git history for the implementation steps.
