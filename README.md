# TaskForge

TaskForge is a Java 21/Spring Boot project for studying reliable background-job processing. Clients submit an allowlisted job through a REST API; PostgreSQL records its state, a worker executes it asynchronously, and clients can inspect attempts and a stored result. The only handler produces a **simulated text report preview**, not a report file or an external effect.

What the current implementation demonstrates:

- PostgreSQL-backed job state, Flyway migrations, and an explicit Java lifecycle.
- Concurrent claims with `FOR UPDATE SKIP LOCKED`, bounded retries, leases, heartbeats, and recovery of expired attempts.
- Optional idempotent submission, ordered attempt history, and durable report-preview retrieval.
- Local health/readiness checks, process-local worker metrics, structured event logs, Java 21/Testcontainers verification, and Docker Compose packaging.

The API is **unauthenticated and intended for trusted local development**. Database fencing prevents stale workers from changing TaskForge state; it does not provide exactly-once execution. See [current architecture](docs/architecture.md), the [copy-paste demo](docs/demo.md), and the [testing guide](docs/testing.md).

## Requirements

- Java 21 JDK
- Maven 3.9 or newer
- Docker Desktop with Docker Engine running (for PostgreSQL and integration tests)

PostgreSQL runs through Docker Compose for local development; a Windows PostgreSQL installation is not needed.

## Quick start

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
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
```

The response is `{"status":"UP"}`. This route only confirms that the HTTP application responds; it does not check a database or a worker. The database connection and Flyway migrations are checked during application startup.

Spring Boot Actuator also provides `/actuator/health/liveness` and `/actuator/health/readiness`. Liveness reports whether the application process is running; it does not depend on PostgreSQL. Readiness includes PostgreSQL health and returns HTTP `503` with status `DOWN` when the database is unavailable. Only Actuator's `health` endpoint is exposed over HTTP; health details and `/actuator/metrics` are not exposed. These unauthenticated checks are for local development, not a public deployment.

To run **both** PostgreSQL and the app in containers instead, set `DATABASE_PASSWORD` in your PowerShell session and use the optional `app` Compose profile:

```powershell
$env:DATABASE_PASSWORD = 'choose-a-local-password'
docker compose --profile app up --build -d --wait
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
```

The image builds the runnable JAR from source with Java 21 and runs it with a Java 21 runtime as a non-root user. Its packaging step skips tests because `mvn verify` runs the full Docker-backed Testcontainers suite separately, both locally and before the image build in CI. Compose waits for PostgreSQL health before starting the app; the app readiness check above confirms that startup and Flyway migrations have completed. The plain `docker compose up -d --wait` command still starts PostgreSQL alone for Maven development.

Both published ports bind to `127.0.0.1`. The container listens on all interfaces **inside its private Docker network** so Compose can forward the host's loopback port. A Maven-started app also defaults to `127.0.0.1` through `SERVER_ADDRESS`; its port defaults to 8080. These unauthenticated APIs and health routes are for trusted local development only. Do not publish or proxy them to an untrusted network without authentication and job ownership checks.

An existing PostgreSQL volume retains the password chosen when it was first initialized. Supply that same password on later starts; changing `DATABASE_PASSWORD` alone does not reset the database. To test with a separate fresh volume without touching existing data, set a distinct `$env:COMPOSE_PROJECT_NAME` before `up` (and use it again for `down`). Stop only a stack you started with `docker compose --profile app down`; this leaves its named volume intact. Never use `down -v` unless you explicitly intend to delete that stack's data.

## Job API

| Endpoint | Purpose | Typical response |
| --- | --- | --- |
| `POST /api/v1/jobs` | Queue an allowlisted report-preview job. | `201 Created`; matching idempotency replay returns `200 OK`. |
| `GET /api/v1/jobs/{id}` | Read current status and attempt count. | `200 OK`; unknown ID returns `404`. |
| `GET /api/v1/jobs/{id}/attempts` | Read ordered execution history. | `200 OK`, including `[]` before the first attempt. |
| `GET /api/v1/jobs/{id}/result` | Read the stored preview after success. | `200 OK`; unavailable results return a safe `409`. |
| `GET /api/v1/health` | Check the legacy HTTP route. | `200 OK` with `{"status":"UP"}`. |

The [demo guide](docs/demo.md) follows one job through submission, status polling, attempt history, and result retrieval. There is no list-all, manual retry, or cancellation endpoint.

Submit a report job from PowerShell while the app is running:

```powershell
$job = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/jobs -ContentType 'application/json' -Body '{"jobType":"GENERATE_REPORT","payload":{"reportName":"Quarterly report"}}'
$job.id
Invoke-RestMethod "http://127.0.0.1:8080/api/v1/jobs/$($job.id)"
```

`POST /api/v1/jobs` without an `Idempotency-Key` header, or with a new key, returns `201 Created`, a `Location` header pointing to `/api/v1/jobs/{id}`, and the saved job. `GET /api/v1/jobs/{id}` returns that job with `200 OK`. The response includes its ID, job type, normalized payload, status, attempt count, creation and update times, and an optional terminal failure reason. New jobs have status `QUEUED` and attempt count `0`; the worker may change the status soon after the response. Poll GET to observe it. A retrying job can show `RETRY_WAIT`; `attemptCount` is the number of attempts **started**, not the number of retries scheduled.

The optional `Idempotency-Key` is trimmed and must then contain 1–128 ASCII letters, digits, `.`, `_`, `~`, or `-`. A supplied invalid key returns `400 Bad Request`; it is never treated as absent. Repeating the same key with the same normalized job type and report name returns `200 OK`, the existing job's **current** representation, and its original `Location`. Reusing that key with a different logical request returns `409 Conflict`. A replay keeps the original job ID but does not preserve the first response snapshot: the first response may show `QUEUED` and a later replay may show `SUCCEEDED`.

Keys are globally scoped because this API has no authentication or client identity. They are retained permanently in this version, with no expiry or cleanup policy. This prevents duplicate job creation for a retained key; it does not guarantee exactly-once handler execution or external effects. During rollout, stop older application instances from accepting submissions before relying on this guarantee, since they do not interpret the header. The API is intended for local development, not public deployment without authentication and job ownership checks.

Only `jobType` and `payload.reportName` are accepted in a submission. The server generates the ID, status, attempt count, and timestamps. The only supported type is `GENERATE_REPORT`; report names must be nonblank, at most 100 characters, and one line. Unknown JSON fields are rejected. Invalid input or a malformed UUID returns `400`; an unknown valid UUID returns `404`. API errors use `code`, `message`, and `fieldErrors` fields without stack traces or database details. There is no list or retry endpoint.

### Attempt history

Inspect a job's execution attempts after submitting it:

```powershell
Invoke-RestMethod "http://127.0.0.1:8080/api/v1/jobs/$($job.id)/attempts"
```

`GET /api/v1/jobs/{id}/attempts` returns a JSON array in `attemptNumber` order. `startedAt` is the recorded or reconstructed start timestamp; `finishedAt` is when that attempt closed, or `null` while it is running. `outcome` describes that attempt: `RUNNING` is open, `RETRY_WAIT` means it ended and a retry was scheduled, and `SUCCEEDED` or `FAILED` records its final result. `failureReason` is a safe display reason for an attempt that failed or scheduled a retry; unfamiliar stored reasons are shown as `Job attempt failed`. `nextEligibleAt` is the time when that attempt's scheduled retry becomes eligible. Inapplicable `finishedAt`, `failureReason`, and `nextEligibleAt` fields are returned as JSON `null`.

The history keeps each attempt's outcome. An earlier attempt can therefore show `RETRY_WAIT` even when a later attempt succeeds. `GET /api/v1/jobs/{id}` reports the separate, current job status, such as `SUCCEEDED`.

`startTimeSource` explains where `startedAt` came from: `RECORDED` means it was saved when the attempt started; `LEGACY_DERIVED` means it was inferred from a pre-Phase-7 `RUNNING` job's last update time; `LEGACY_SYNTHETIC` means a pre-Phase-7 terminal job's creation time was used for reconstruction. The legacy values are not observed start times.

An existing job with no started attempts returns `200 OK` with `[]`. An unknown valid UUID returns the existing safe `404` response; a malformed UUID returns `400`. This endpoint is currently unauthenticated and intended for local development only. Do not expose it publicly until authentication and job ownership checks are implemented.

### Report preview result

After a job succeeds, retrieve its stored text preview with `GET /api/v1/jobs/{id}/result`. A successful response is `200 OK` with `jobId`, the successful `attemptNumber`, and `preview`. This reads PostgreSQL; it does not rerun the handler or create a report file.

An existing `QUEUED`, `RUNNING`, or `RETRY_WAIT` job returns `409 Conflict` with code `RESULT_NOT_READY`. A `FAILED` job returns `409` with `RESULT_FAILED`. A historical `SUCCEEDED` job without a stored preview returns `409` with `RESULT_UNAVAILABLE`; TaskForge does not invent a result for it. These responses use the usual `code`, `message`, and `fieldErrors` error body. An unknown valid job ID returns `404 JOB_NOT_FOUND`, and a malformed UUID returns `400 INVALID_REQUEST`. Like the other job APIs, this endpoint is unauthenticated and intended only for local development.

## Database-backed worker

One scheduled worker cycle first checks one expired `RUNNING` job for recovery, then claims an eligible `QUEUED` or `RETRY_WAIT` job with PostgreSQL `FOR UPDATE SKIP LOCKED`. The claim transaction applies `start()`, records the numbered attempt, sets a lease, and commits `RUNNING` before the handler executes. A separate heartbeat thread can renew the lease during a slow handler. Completion and recovery use short, guarded transactions that lock the job and update its exact attempt record together. The handler runs outside database transactions. A competing worker skips a locked row and may take another job.

Only an explicitly retryable handler error or an expired lease schedules another attempt. Other handler errors fail permanently with a fixed safe reason. The default maximum is three started attempts. After failed attempt 1, the job waits 5 seconds; after attempt 2, 10 seconds; after attempt 3 it becomes `FAILED`. The delay doubles up to a 5-minute cap. PostgreSQL stores the next eligible time; no thread or transaction waits through backoff. The only production handler builds a deterministic text report preview. Successful completion stores that preview with the successful attempt in PostgreSQL. No report file, download, or external service exists. `SUCCEEDED` means this simulation completed, not that a deliverable report was generated.

The worker checks at most one abandoned job and processes at most one eligible job per tick. `WORKER_POLL_DELAY_MS` defaults to `1000` milliseconds between completed ticks. Set `WORKER_SCHEDULING_ENABLED=false` to disable automatic polling, for example while inspecting queued jobs locally. `WORKER_MAX_ATTEMPTS`, `WORKER_RETRY_BASE_DELAY_MS`, `WORKER_RETRY_DELAY_CAP_MS`, `WORKER_LEASE_DURATION_MS`, and `WORKER_HEARTBEAT_INTERVAL_MS` have safe defaults shown in `.env.example`. Invalid limits prevent startup. The maximum is stored per submitted job; changing it later affects only new jobs. The POST endpoint still saves jobs without executing handlers in the request thread.

The built-in fixed-delay scheduler runs one handler at a time per application instance. Its single heartbeat thread therefore serves one scheduled claim at a time. If an instance later runs handlers concurrently, use a bounded heartbeat executor and test that a blocked renewal cannot delay another claim past its lease.

Worker metrics are recorded in the local Micrometer registry: `taskforge.worker.claims` counts committed claims; `taskforge.worker.recoveries` counts committed lease recoveries; and `taskforge.worker.completions` counts committed attempt closures with the bounded `outcome` tag `SUCCEEDED`, `FAILED`, or `RETRY_WAIT` (including closures caused by recovery). `taskforge.worker.ownership.losses` counts active heartbeats whose lease renewal was rejected; a database error leaves ownership unknown and is not counted as a confirmed loss. `taskforge.worker.handler.duration` times handler calls, including calls that throw, but not missing-handler lookups. Claim, recovery, and completion counters update only after their database transactions commit. These metrics reset on process restart and are separate for each instance, so query PostgreSQL for authoritative job totals. For now, inspect registrations and values through `WorkerMetricsTest` and PostgreSQL-backed worker tests or an IDE debugger on the `MeterRegistry` bean. `/actuator/metrics` remains unexposed; do not enable it on a public, unauthenticated server.

Worker logs use searchable `event=...` fields. `job_claimed` means a numbered attempt committed as `RUNNING`; `lease_recovered` records a committed recovery and its bounded outcome; `job_completed` records a committed handler completion with outcome `SUCCEEDED`, `FAILED`, or `RETRY_WAIT`. A recovery emits `lease_recovered`, not a second `job_completed` line. `ownership_lost` means a heartbeat received a definite renewal rejection; `lease_renewal_error` means the renewal result is unknown. `handler_failed` identifies a retryable, missing-handler, or permanent handler failure before the database outcome is known; `worker_cycle_failed` identifies an uncaught cycle failure. Failure logs include only the exception class name. These logs complement process-local metrics and the authoritative PostgreSQL records. They deliberately omit job IDs and other unbounded or sensitive data, so they cannot independently correlate one job's events across instances. Do not log payloads, report previews, idempotency keys, credentials, SQL parameters, or raw exception messages.

Multiple TaskForge instances can claim different jobs. Skipping a locked older row means processing order is not strictly first-in-first-out. If a worker crashes after claiming, its lease eventually expires and another worker can schedule a retry. An old worker cannot renew or save a result after its lease expires or a newer attempt starts. This fences **PostgreSQL state**, not execution or external effects: an old handler may still run, and a successful handler can be repeated if its final save fails. This version does not guarantee exactly-once execution or effects. The report preview has no external side effect. See [ADR 0005](docs/adr/0005-retries-leases-recovery.md).

**Upgrade from Phase 6:** Stop and confirm all Phase 6 application instances have exited before applying Flyway V3 or starting Phase 7 instances. Phase 6 workers can write terminal state without a lease or attempt-history check; mixed versions cannot safely share this schema. V3 marks any existing `RUNNING` job's lease expired so the new worker can recover it. Historical terminal attempts did not record an actual start time; their backfilled start is explicitly marked synthetic.

## Configuration reference

These are the current local defaults from `application.yml`, `compose.yaml`, and `.env.example`. Compose overrides the app's database URL and bind address inside its container.

| Variable | Default or requirement | Use |
| --- | --- | --- |
| `SERVER_ADDRESS` | `127.0.0.1`; Compose app uses `0.0.0.0` internally | Spring HTTP bind address. Compose still publishes the host port only on `127.0.0.1`. |
| `SERVER_PORT` | `8080` | Spring HTTP port; Compose uses it for the host port and keeps container port 8080. |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/taskforge` | JDBC URL for Maven startup; Compose app uses `postgres:5432` and `DATABASE_NAME`. |
| `DATABASE_NAME`, `DATABASE_USERNAME` | `taskforge` | Compose PostgreSQL database and user; username also defaults in Spring. |
| `DATABASE_PORT` | `5432` | Host-published PostgreSQL port, bound to loopback. Adjust `DATABASE_URL` for Maven if changed. |
| `DATABASE_PASSWORD` | Required for Compose; no usable Spring default | Set in the shell or an ignored Compose `.env`; never commit it. |
| `WORKER_SCHEDULING_ENABLED` | `true` | Set `false` to disable automatic polling. |
| `WORKER_POLL_DELAY_MS` | `1000` | Fixed delay after a scheduled worker cycle. |
| `WORKER_MAX_ATTEMPTS` | `3` | Maximum started attempts for newly submitted jobs. |
| `WORKER_RETRY_BASE_DELAY_MS`, `WORKER_RETRY_DELAY_CAP_MS` | `5000`, `300000` | Exponential retry delay and cap, in milliseconds. |
| `WORKER_LEASE_DURATION_MS`, `WORKER_HEARTBEAT_INTERVAL_MS` | `30000`, `10000` | Lease and renewal interval, in milliseconds. |

To change the HTTP port for a Maven run, set `$env:SERVER_PORT = '8081'` in that PowerShell session. `.env.example` contains names and placeholders only. Compose can read an ignored `.env` file; Spring Boot reads shell environment variables, not that file. Never commit a real `.env` file.

`mvn verify` starts an isolated PostgreSQL Testcontainers instance, so Docker must be running even if the Compose database is stopped. Stop the local database with `docker compose down` when finished. The named volume retains its data; deleting it would remove local jobs.

The PostgreSQL JDBC driver sends the JVM timezone name when connecting. This Windows JDK reports `Asia/Calcutta`, which PostgreSQL 17.11 rejects. Maven configures UTC for test and `spring-boot:run` JVMs. If you run the packaged jar directly, use `java -Duser.timezone=UTC -jar target/taskforge-0.1.0-SNAPSHOT.jar`. This changes only that Java process and does not change Windows settings or how instants are stored.

On some Codex terminals, Java may report `user.home=C:\` even when `USERPROFILE` points to the Windows account. If Maven reports an inaccessible `C:\.m2\repository`, set `$env:MAVEN_OPTS = "-Duser.home=$env:USERPROFILE"` for that terminal session, then rerun Maven. This does not change machine-wide configuration.

## Known limitations and architecture

- The API has no authentication, user ownership, quotas, or rate limiting. Loopback binding is a local development boundary, not authorization.
- `GENERATE_REPORT` creates a bounded text preview. No downloadable report, email, payment, or other external side effect is implemented.
- Worker claims and database writes are fenced, but an old handler may keep running after losing its lease. A crash after handler work can cause another attempt; exactly-once execution and external effects are not guaranteed.
- Idempotency keys are globally scoped and retained without expiry. They prevent duplicate **job creation** for a retained key, not duplicate handler effects.
- Metrics reset on process restart and are not aggregated across instances. `/actuator/metrics` is not exposed over HTTP.
- The built-in scheduler runs one handler at a time per instance. There is no Redis queue, cloud deployment, or public security configuration.

The [architecture guide](docs/architecture.md) explains the current components, state machine, tables, and transaction boundaries. PostgreSQL is the authoritative record of job state and result; Redis is not required by this implementation.

```text
HTTP client → JobController → JobApplicationService → domain Job → JpaJobStore → PostgreSQL
WorkerScheduler → JobWorker → JpaJobStore → PostgreSQL
                          → JobHandlerRegistry → ReportJobHandler
HTTP client → HealthController → HealthResponse JSON
```

`com.taskforge` is the application root. The `api` package handles HTTP and safe error mapping; `application` coordinates use cases; `domain` holds pure-Java lifecycle rules; `persistence` maps jobs to PostgreSQL; and `worker` schedules and runs allowlisted handlers. Flyway creates the schema and Hibernate validates it.

ADRs [0001](docs/adr/0001-job-domain.md), [0002](docs/adr/0002-postgres-persistence.md), [0003](docs/adr/0003-first-worker.md), [0004](docs/adr/0004-concurrent-claiming.md), and [0005](docs/adr/0005-retries-leases-recovery.md) are historical design records. In particular, ADRs 0001–0003 describe earlier single-attempt or single-worker phases; read the current architecture guide for today's behavior.

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
11. Durable report previews and result retrieval API (Phase 11)
12. Logs, health, and metrics (Phase 12)
13. Local Docker packaging and reproducible setup (Phase 13)
14. Architecture and portfolio documentation (Phase 14)

Each phase is reviewed and tested before the next begins. See the Git history for the implementation steps.
