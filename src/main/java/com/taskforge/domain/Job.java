package com.taskforge.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class Job {

    private final UUID id;
    private final JobPayload payload;
    private final JobStatus status;
    private final int attemptCount;
    private final int maxAttempts;
    private final Instant nextEligibleAt;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final String failureReason;

    private Job(UUID id, JobPayload payload, JobStatus status, int attemptCount, int maxAttempts,
                Instant createdAt, Instant updatedAt, Instant nextEligibleAt, String failureReason) {
        this.id = Objects.requireNonNull(id, "id");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot be before createdAt");
        }
        if (maxAttempts < 1 || attemptCount < 0 || attemptCount > maxAttempts
                || (status == JobStatus.QUEUED && attemptCount != 0)
                || (status != JobStatus.QUEUED && attemptCount == 0)
                || (status == JobStatus.RETRY_WAIT && attemptCount >= maxAttempts)) {
            throw new IllegalArgumentException("attemptCount is inconsistent with status");
        }
        if ((status == JobStatus.RETRY_WAIT) != (nextEligibleAt != null)) {
            throw new IllegalArgumentException("nextEligibleAt is inconsistent with status");
        }
        if ((status == JobStatus.FAILED) != (failureReason != null)) {
            throw new IllegalArgumentException("failureReason is inconsistent with status");
        }

        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
        this.nextEligibleAt = nextEligibleAt;
        this.failureReason = failureReason;
    }

    public static Job create(UUID id, JobPayload payload, Instant createdAt) {
        return create(id, payload, createdAt, 3);
    }

    public static Job create(UUID id, JobPayload payload, Instant createdAt, int maxAttempts) {
        return new Job(id, payload, JobStatus.QUEUED, 0, maxAttempts, createdAt, createdAt, null, null);
    }

    public Job start(Instant at) {
        requireTransitionTo(JobStatus.RUNNING);
        if (attemptCount >= maxAttempts) {
            throw new IllegalStateException("Attempt limit reached");
        }
        if (status == JobStatus.RETRY_WAIT && at.isBefore(nextEligibleAt)) {
            throw new IllegalStateException("Retry is not eligible yet");
        }
        return transitionTo(JobStatus.RUNNING, at, attemptCount + 1, null, null);
    }

    public Job succeed(Instant at) {
        requireTransitionTo(JobStatus.SUCCEEDED);
        return transitionTo(JobStatus.SUCCEEDED, at, attemptCount, null, null);
    }

    public Job fail(String reason, Instant at) {
        requireTransitionTo(JobStatus.FAILED);
        String normalizedReason = normalizeFailureReason(reason);
        return transitionTo(JobStatus.FAILED, at, attemptCount, null, normalizedReason);
    }

    public Job scheduleRetry(Instant nextEligibleAt, Instant at) {
        requireTransitionTo(JobStatus.RETRY_WAIT);
        if (attemptCount >= maxAttempts) {
            throw new IllegalStateException("Attempt limit reached");
        }
        Objects.requireNonNull(nextEligibleAt, "nextEligibleAt");
        return transitionTo(JobStatus.RETRY_WAIT, at, attemptCount, nextEligibleAt, null);
    }

    private void requireTransitionTo(JobStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Cannot transition job from " + status + " to " + next);
        }
    }

    private Job transitionTo(JobStatus next, Instant at, int nextAttemptCount,
                             Instant nextEligibleAt, String nextFailureReason) {
        Objects.requireNonNull(at, "at");
        if (at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Transition time cannot be before updatedAt");
        }
        return new Job(id, payload, next, nextAttemptCount, maxAttempts, createdAt, at,
                nextEligibleAt, nextFailureReason);
    }

    public static String normalizeFailureReason(String reason) {
        String normalized = Objects.requireNonNull(reason, "reason").strip();
        if (normalized.isBlank() || normalized.length() > 500
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("failureReason must be one line with 1 to 500 characters");
        }
        return normalized;
    }

    public UUID id() {
        return id;
    }

    public JobType jobType() {
        return payload.jobType();
    }

    public JobPayload payload() {
        return payload;
    }

    public JobStatus status() {
        return status;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Optional<Instant> nextEligibleAt() {
        return Optional.ofNullable(nextEligibleAt);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }
}
