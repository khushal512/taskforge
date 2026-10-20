# ADR 0001: Job domain and initial lifecycle

Status: Accepted for Phase 2

## Context

TaskForge needs a job model before adding persistence, APIs, or workers. The model must make illegal lifecycle changes visible and testable without depending on Spring or a database.

## Decision

`Job` is an immutable domain object. `Job.create(id, payload, createdAt)` creates a `QUEUED` job with zero attempts. A job ID is a UUID supplied by the caller. The payload is a known, validated type; the first supported type is `ReportPayload`, which requires a one-line report name of 1 to 100 characters and identifies `GENERATE_REPORT`. The job type is derived from the payload, so two separate values cannot disagree.

| From | To | Operation | Effect |
| --- | --- | --- | --- |
| `QUEUED` | `RUNNING` | `start(at)` | Increase attempt count from 0 to 1. |
| `RUNNING` | `SUCCEEDED` | `succeed(at)` | Finish without a failure reason. |
| `RUNNING` | `FAILED` | `fail(reason, at)` | Finish with a short reason. |
| `SUCCEEDED`, `FAILED` | None | None | Terminal in Phase 2. |

Every other transition throws `IllegalStateException`. A transition timestamp cannot be before `updatedAt`; equal timestamps are allowed because two events can share clock precision. Failed validation leaves the original object unchanged. `createdAt` never changes. A failure reason is present only for `FAILED`, is one line, and is limited to 500 characters.

The domain does not read the system clock or generate IDs. Application code will supply both, making tests deterministic. No domain class uses HTTP, JPA, Redis, or Spring annotations.

## Consequences and limits

- A worker has not been implemented. The attempt count records a start but does not enforce a retry policy yet.
- `FAILED` is terminal for now. Phase 6 must explicitly revise the transition table if retries return a failed attempt to the queue; it must preserve attempt history.
- There is no cancellation, scheduling, maximum-attempt setting, execution timestamp, or persisted state yet. Those fields will be introduced with the behavior that needs them.
- Limiting failure text does not guarantee that it is safe to show to users. A later application layer must remove secrets and internal details before passing a reason into the domain.
- Immutability prevents accidental in-process mutation, but it does not coordinate multiple workers. Database transactions and claim rules must provide concurrency safety in later phases.
- Persistence may use a separate JPA entity and mapper rather than putting database annotations into this domain model.

## Alternatives considered

A mutable job with public status setters would be easy to map to a database, but it would allow callers to bypass the transition rules. Adding retry and scheduling fields now would require choosing policies before they are tested. This phase keeps only the fields needed to explain and verify the first lifecycle.
