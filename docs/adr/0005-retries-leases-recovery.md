# ADR 0005: Bounded retries and fenced lease recovery

## Status

Accepted for Phase 7. This extends ADR 0001's state machine and ADR 0004's concurrent claim. Those ADRs remain records of earlier phases.

## Context

The Phase 6 claim prevents two workers from starting the same queued attempt, but a crash after committing `RUNNING` strands the job. A slow old worker could also finish after another instance has recovered the job. The old mapper could reconstruct only one attempt, and its generic `save(Job)` allowed a worker to overwrite a newer state.

## Decision

Add `RETRY_WAIT` and allow `QUEUED → RUNNING`, `RUNNING → RETRY_WAIT | SUCCEEDED | FAILED`, and `RETRY_WAIT → RUNNING`. Terminal states stay terminal. `attemptCount` counts starts and is never reset. It doubles as a fencing value. The maximum is stored per job; the default is three starts. Explicitly retryable handler failures and expired leases use `min(5 minutes, 5 seconds × 2^(attemptCount−1))` backoff, measured from PostgreSQL time. All other handler failures are terminal. The attempt limit is checked before scheduling a retry. The only production handler remains a simulated in-memory report preview.

V3 adds `max_attempts`, `next_eligible_at`, `lease_expires_at`, and `job_attempts`. An attempt row is inserted on each claim and closed on success, failure, or recovery. The mapper replays consecutive attempt rows through the domain transitions and compares the result with the stored aggregate. Database constraints guard row-local rules; transactional store methods and replay validation guard cross-table rules. A terminal legacy row has no recorded start timestamp: V3 uses `created_at` for legal replay but labels the attempt `LEGACY_SYNTHETIC`. A legacy `RUNNING` row derives its start from its `updated_at` and is marked `LEGACY_DERIVED`.

Worker lifecycle writes use only `claimNextEligible`, `renewLease`, `recoverOneExpired`, `completeSuccess`, and `completeFailure`. Each locks the job row and checks status, attempt number, and the lease or retry eligibility as appropriate. An attempt update targets `(job_id, attempt_number)` and requires it to be open. The job and attempt changes commit in one short transaction. Creation alone uses `createQueued`; the worker has no generic save path. The claim transaction commits before handler execution. A dedicated heartbeat thread renews while a handler runs. A rejected renewal marks ownership lost; a database error marks it unknown. Either causes the worker to discard the eventual handler result. Completion still checks ownership in PostgreSQL.

For each locked decision, read PostgreSQL `clock_timestamp()` after acquiring the row lock. A lease is owned only while `lease_expires_at > dbNow`; expiry and retry eligibility use `<= dbNow`. The domain transition time is `max(stored updatedAt, dbNow)` to prevent time moving backward. Retry eligibility is `dbNow + backoff`, independent of a future `updatedAt`; this avoids an extra delay from host clock skew. `nextEligibleAt` may therefore precede `updatedAt`, but the store still requires PostgreSQL time to reach it before claiming. Row locks serialize heartbeat, recovery, and completion, and release at transaction end.

## Consequences and limits

- A stale attempt cannot renew or write TaskForge job or attempt state. A blocked handler may nevertheless continue running after losing ownership.
- A crash after handler work but before terminal commit can cause another attempt. Database fencing does not guarantee exactly-once execution or external effects. Future effectful handlers require their own idempotency or external fencing.
- The heartbeat has its own scheduler thread. A failed renewal stops heartbeats and leaves recovery to the lease expiry. A healthy but unusually slow handler can retain ownership while renewals succeed. The built-in scheduler processes one handler at a time per instance, so one heartbeat thread serves its scheduled claim. Parallel handlers within one instance would require a bounded heartbeat executor and a blocked-renewal isolation test.
- Retry delays hold no worker thread or database transaction. There is no jitter, submission idempotency, Redis queue, or user-facing attempt-history endpoint in this phase.
- All Phase 6 worker instances must be stopped before V3 migration and before Phase 7 workers start. Old workers do not follow the lease or attempt-history protocol and cannot safely run alongside the new version.

## Alternatives considered

A random claim token would also fence stale workers, but the existing monotonically increasing attempt number already uniquely identifies each started attempt for a job. A generic JPA save with optimistic locking would still require careful coordination of attempt records and could leave a bypass path. Short row-locking transactions fit the existing PostgreSQL claim strategy. Redis adds no needed authority or recovery guarantee here.
