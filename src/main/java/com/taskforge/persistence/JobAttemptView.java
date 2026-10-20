package com.taskforge.persistence;

import java.time.Instant;
import java.util.Objects;

/** Immutable, validated history data for application callers; not a JPA entity. */
public record JobAttemptView(int attemptNumber, Instant startedAt, Instant finishedAt,
                             StartTimeSource startTimeSource, Outcome outcome,
                             String failureReason, Instant nextEligibleAt) {

    public enum StartTimeSource { RECORDED, LEGACY_DERIVED, LEGACY_SYNTHETIC }

    public enum Outcome { RUNNING, RETRY_WAIT, SUCCEEDED, FAILED }

    static JobAttemptView from(AttemptRecord attempt) {
        Objects.requireNonNull(attempt, "attempt");
        return new JobAttemptView(attempt.number(), attempt.startedAt(), attempt.finishedAt(),
                StartTimeSource.valueOf(attempt.startTimeSource()),
                attempt.outcome() == null ? Outcome.RUNNING : Outcome.valueOf(attempt.outcome()),
                safeFailureReason(attempt.failureReason()), attempt.nextEligibleAt());
    }

    private static String safeFailureReason(String storedReason) {
        if (storedReason == null) {
            return null;
        }
        return switch (storedReason) {
            case "Temporary job execution failure", "No handler is available for this job type",
                    "Job execution failed", "Worker lease expired" -> storedReason;
            default -> "Job attempt failed";
        };
    }
}
