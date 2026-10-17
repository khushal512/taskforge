# ADR 0003: First database-backed worker

## Status

Accepted for Phase 5. The claim algorithm is intentionally limited to one application worker instance.

## Decision

The API commits a new `QUEUED` job and returns immediately. A Spring scheduled adapter triggers one `JobWorker.processOne()` cycle per tick. The cycle calls a public `JpaJobStore` method on a separate Spring bean. That method finds the oldest queued row, reconstructs the domain object, calls `Job.start()`, saves it, and commits its short transaction before returning the `RUNNING` job. The worker then invokes a handler without a transaction. It calls `Job.succeed()` or `Job.fail()` and saves the terminal state in another short transaction. The domain remains the authority for legal transitions.

Handlers are selected through an explicit `JobType` registry. Duplicate registrations fail during startup; a missing registration marks a claimed job `FAILED`. The only handler accepts `ReportPayload` and creates a deterministic preview string in memory. The string is not persisted or exposed. This avoids implying that a real report system exists.

A partial index on queued rows supports the oldest-job lookup. PostgreSQL remains the source of truth. The worker processes at most one job per scheduled tick. Tests can call a cycle directly with scheduling disabled, avoiding timing-dependent sleeps.

## Transaction and failure boundaries

| Boundary | Database state | Handler running? |
| --- | --- | --- |
| Before claim | `QUEUED` | No |
| Claim transaction commits | `RUNNING`, attempt count `1` | No |
| Handler executes | `RUNNING` | Yes, outside a transaction |
| Final save commits | `SUCCEEDED` or `FAILED` | No |

Handler exceptions become `FAILED` with a fixed safe reason. A scheduler-level exception is logged without payload or exception message, and the next tick is still allowed. A failure to save the terminal state, or a process crash after the claim, can leave a job in `RUNNING`. There is no retry, lease, timeout, or stale-job recovery in this phase. The report preview has no external side effect; future handlers will need explicit idempotency and recovery design.

## Limits and future work

The queued-row read and write are **not an atomic multi-worker claim**. Two application instances could select and execute the same job. Deploy only one worker-enabled instance. A later phase will introduce database locking or another atomic claim and test competing workers. No exactly-once guarantee is made. There is no result storage, retry policy, job cancellation, Redis coordination, or authentication in this phase.
