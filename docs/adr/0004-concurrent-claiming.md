# ADR 0004: Concurrent PostgreSQL job claiming

## Status

Accepted for Phase 6. This supersedes the single-worker claim limitation in ADR 0003; ADR 0003 remains a record of Phase 5.

## Context

The Phase 5 worker read the oldest queued row without a lock. Two application instances could read the same row before either saved `RUNNING`, then both execute its handler. PostgreSQL already stores the authoritative job state and has an index on queued jobs ordered by creation time and ID.

## Decision

The repository selects one row using `WHERE status = 'QUEUED' ORDER BY created_at, created_at_nano_remainder, id LIMIT 1 FOR UPDATE SKIP LOCKED`. The store method keeps this select, domain `Job.start()`, and JPA save in one short Spring transaction. Its caller, `JobWorker`, is a separate Spring bean without a surrounding transaction. The transactional proxy commits `RUNNING` before `JobWorker` invokes the handler. Handler execution and terminal-state persistence remain outside the claim transaction.

PostgreSQL locks the selected row until the claim transaction commits or rolls back. A concurrent claim skips that row and can select a different queued row. Once committed as `RUNNING`, the first row no longer matches the queue predicate. A rollback releases the lock and leaves the job `QUEUED`, so a later poll can claim it. The existing partial index `jobs_queued_order_idx` matches the queue predicate and ordering; the planner decides whether to use it.

This is a PostgreSQL-specific query in the persistence layer. The domain model, API, payload validation, and state machine do not depend on the locking syntax. No migration is needed.

## Consequences and limits

- Multiple worker instances using this claim path do not receive the same committed queued-job claim. They can process different jobs concurrently.
- `SKIP LOCKED` favors available work over strict first-in-first-out order; an older locked job can be passed over.
- A process crash before claim commit rolls back to `QUEUED`. A crash after commit can leave `RUNNING` indefinitely because this phase has no lease or recovery.
- A handler may finish while the final save fails, leaving `RUNNING`. This design does not guarantee exactly-once execution or effects.
- Transactions must stay short: do not invoke handlers before the claim method returns and commits. A separate integration test checks that the handler has no active transaction.
- This claim protocol does not protect against other code that directly overwrites job rows. Such code must use the domain lifecycle and compatible transaction rules.

## Alternatives considered

An `UPDATE ... RETURNING` claim could be one SQL statement, but directly changing status and attempt count there would bypass `Job.start()` without extra validation and mapping. Optimistic versioning would need a schema change and collision handling. Transactional row locking fits the current JPA mapping and domain rules with fewer changes.
