# ADR 0002: PostgreSQL persistence for jobs

## Decision

PostgreSQL is the authoritative job store. Flyway V1 creates the `jobs` table; Hibernate validates the schema (`ddl-auto: validate`) and must not create or update it. The domain `Job` remains free of persistence annotations. A persistence adapter converts it to a JPA entity with a JSONB payload and back.

The first query is by UUID, so the primary-key index is sufficient. Worker claim and scheduling indexes will be designed when those queries exist.

## Reconstruction and exactness

The current domain lifecycle has one attempt: `QUEUED → RUNNING → SUCCEEDED/FAILED`. A row is reconstructed by creating a queued job and replaying legal domain transitions. For a terminal row, the intermediate start time is set to creation time because the current model does not store a separate start time. The mapper checks the reconstructed ID, type, canonical payload, status, attempt count, creation time, update time, and failure reason against the row. A mismatch raises an exception. No public domain restoration shortcut is added.

PostgreSQL timestamps have microsecond precision; Java `Instant` has nanosecond precision. Each time is stored as a `timestamptz` truncated to microseconds plus a 0–999 nanosecond remainder. Recombining them preserves the exact `Instant`, including times created by `Instant.now()`.

The adapter's `save` and `findById` methods each run in a Spring transaction. At present a save is one row. Later multi-row operations, claims, and retry history will need deliberate transaction boundaries. There is no optimistic lock or concurrent worker claim in this phase.

## Constraints and limits

SQL enforces the UUID key, known current job type and status, JSON object payload, nonnegative lifecycle-specific attempt count (zero while queued, one after start), ordered timestamps, bounded nanosecond remainders, and presence of a nonblank failure reason only for failed jobs. This schema intentionally describes the current single-attempt lifecycle; retries will require a migration.

The database cannot reasonably reproduce every Java validation rule. In particular, `ReportPayload` strips Unicode whitespace, limits its name to 100 Java characters, and rejects ISO control characters. `Job.fail` similarly normalizes and validates its reason. PostgreSQL's text length, trimming, JSON checks, and character classes do not match Java's rules exactly. The domain constructors and mapper enforce these on reads and writes, and the mapper rejects a row if normalization would change the stored value. Direct SQL writers must therefore follow the same domain rules; the table checks alone are not a complete substitute.

Payload JSONB is suitable for the current allowlisted report payload. The mapper requires exactly one `reportName` string for `GENERATE_REPORT`; it does not execute user-supplied code. Adding another job type requires an explicit domain type, mapper case, and migration of the type constraint.

## Verification

Testcontainers starts a fresh PostgreSQL 17.11 database. The integration tests check Flyway V1, a repeated no-op migration, exact round trips for all four statuses, and rejection of a stored payload that would normalize differently. The existing domain and health tests continue to run without the Compose database.
