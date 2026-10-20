# Testing TaskForge

TaskForge's verification suite covers pure-Java domain rules, HTTP behavior, and PostgreSQL-backed transactions. Integration tests use real PostgreSQL 17.11 containers; an in-memory database would not prove the locking and migration behavior described here.

## Run the full suite

From the repository root, use a Java 21 JDK, Maven 3.9+, and a running Docker Engine:

```powershell
java -version
mvn -version
docker version
mvn --batch-mode --no-transfer-progress verify
```

`mvn verify` compiles the project, runs the JUnit 5 suite, and packages the Spring Boot JAR. Mockito is used for focused tests; Testcontainers starts isolated PostgreSQL instances for database tests. The Compose PostgreSQL service does **not** need to be running. Maven test reports appear under ignored `target/surefire-reports/`; inspect the command's final test totals and build result rather than assuming a fixed count. Do not skip integration tests to report a green build.

The Windows Maven configuration sets UTC for tests and `spring-boot:run`; the Dockerfile sets UTC for the packaged container process. If this terminal reports an inaccessible `C:\.m2\repository`, the README documents a session-only `MAVEN_OPTS` fix. If Testcontainers cannot connect to Docker Engine, check Docker Desktop and terminal access before interpreting the result as a code failure; do not disable tests or change security settings silently.

## What the tests establish

| Concern | Representative tests | Evidence and limit |
| --- | --- | --- |
| Domain transitions and validation | `JobTest`, `JobStatusTest`, `ReportPayloadTest`, `ReportPreviewTest` | Legal and illegal transitions, bounded payloads and previews. These are in-memory rules, not database concurrency tests. |
| REST submission, status, and history | `JobApiIntegrationTest`, `JobAttemptHistoryIntegrationTest` | HTTP validation and safe responses; PostgreSQL persistence and ordered, validated attempts. |
| Competing claims | `ConcurrentJobClaimIntegrationTest` | Two claimers compete through PostgreSQL; a held row is skipped, and rollback makes it claimable again. This checks the claim protocol, not exactly-once handler effects. |
| Retry and lease recovery | `RetryRecoveryIntegrationTest`, `LeaseOwnershipTest`, `JobWorkerIntegrationTest` | Due-time and attempt-limit behavior, competing recoveries, stale heartbeat/completion rejection, and worker outcomes. Latches and bounded waits coordinate important races. |
| Migrations and old rows | `JpaJobStoreIntegrationTest`, `LegacyMigrationTest`, `SubmissionKeyMigrationTest`, `JobResultMigrationTest` | Flyway schema creation/upgrade and selected legacy-row compatibility, including synthetic timestamp labels and missing historical results. They do not test every possible future upgrade path. |
| Idempotent submission | `KeyedSubmissionStoreIntegrationTest`, `JobIdempotencyIntegrationTest`, `JobApiIntegrationTest` | PostgreSQL uniqueness, rollback of a losing provisional job, matching replay, mismatch conflict, and concurrent HTTP submissions. This protects job creation only. |
| Stored result | `JobResultCompletionIntegrationTest`, `JobResultApiIntegrationTest`, `ConsistentJobReadIntegrationTest` | Owned-attempt result storage, rollback on failed insert, stale-writer rejection, safe GET behavior, and consistent read snapshots. The handler produces only a simulated preview. |
| Health and observations | `HealthReadinessIntegrationTest`, `WorkerMetricsTest`, worker/store tests | Liveness stays up when the isolated database stops while readiness goes down; metrics are registered and updated at the intended points. Metrics remain process-local. |

Several integration tests disable automatic worker scheduling and invoke a cycle directly. This makes state transitions and race coordination more deterministic than waiting for a timer. PostgreSQL `FOR UPDATE SKIP LOCKED`, unique constraints, leases, and Flyway migrations are exercised against Testcontainers rather than inferred from mocks.

## CI and packaging

[GitHub Actions](../.github/workflows/ci.yml) runs on pushes to `codex/**` and pull requests targeting `main`. Its Ubuntu 24.04 job checks out the code, sets up Temurin Java 21 with Maven caching, checks Docker Engine, runs the full `mvn --batch-mode --no-transfer-progress verify` command, and only then builds the application image. A failing step fails the job. The workflow has read-only repository permission, uses no project secret, and does not publish an image.

The [Dockerfile](../Dockerfile) skips tests during image packaging because the CI job verifies them first. A local `docker compose --profile app up --build` can build independently, so run `mvn verify` separately when you want local test evidence. A successful local build does not prove a GitHub Actions run passed; check the actual run for the commit being presented.

## What remains unproven

The suite does not establish performance under load, safe public exposure, authenticated multi-user isolation, exactly-once execution, or exactly-once effects in external systems. The final release check should also use a clean clone and perform the [HTTP demo](demo.md) against the packaged application; unit and integration tests alone do not prove that the documented startup commands work on a new machine. See the [architecture guide](architecture.md) for the transaction and failure boundaries behind these tests.
