package com.taskforge.persistence;

import com.taskforge.domain.Job;
import com.taskforge.domain.JobPayload;
import com.taskforge.domain.JobType;
import com.taskforge.domain.JobStatus;
import com.taskforge.domain.ReportPayload;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
class JobMapper {

    JobEntity toEntity(Job job, Instant leaseExpiresAt) {
        Objects.requireNonNull(job, "job");
        return new JobEntity(job.id(), job.jobType(), payloadToMap(job.payload()), job.status(),
                job.attemptCount(), job.maxAttempts(), job.nextEligibleAt().map(JobMapper::timestamp).orElse(null),
                job.nextEligibleAt().map(JobMapper::remainder).orElse((short) 0),
                leaseExpiresAt == null ? null : timestamp(leaseExpiresAt),
                timestamp(job.createdAt()), remainder(job.createdAt()),
                timestamp(job.updatedAt()), remainder(job.updatedAt()), job.failureReason().orElse(null));
    }

    Job toDomain(JobEntity entity, List<AttemptRecord> attempts) {
        Objects.requireNonNull(entity, "entity");
        JobPayload payload = payloadFromMap(entity.jobType(), entity.payload());
        Instant createdAt = instant(entity.createdAt(), entity.createdAtNanoRemainder());
        Instant updatedAt = instant(entity.updatedAt(), entity.updatedAtNanoRemainder());

        Job restored = Job.create(entity.id(), payload, createdAt, entity.maxAttempts());
        int expectedNumber = 1;
        for (AttemptRecord attempt : attempts) {
            if (!entity.id().equals(attempt.jobId()) || attempt.number() != expectedNumber++
                    || !List.of("RECORDED", "LEGACY_DERIVED", "LEGACY_SYNTHETIC")
                    .contains(attempt.startTimeSource())
                    || (!attempt.startTimeSource().equals("RECORDED") && attempt.number() != 1)
                    || (attempt.startTimeSource().equals("LEGACY_SYNTHETIC")
                    && !attempt.startedAt().equals(createdAt))
                    || !validAttemptFields(attempt)) {
                throw new IllegalStateException("Stored attempt sequence is invalid: " + entity.id());
            }
            restored = restored.start(attempt.startedAt());
            if (attempt.outcome() != null) {
                restored = switch (attempt.outcome()) {
                    case "SUCCEEDED" -> restored.succeed(attempt.finishedAt());
                    case "FAILED" -> restored.fail(attempt.failureReason(), attempt.finishedAt());
                    case "RETRY_WAIT" -> restored.scheduleRetry(attempt.nextEligibleAt(), attempt.finishedAt());
                    default -> throw new IllegalStateException("Unknown stored attempt outcome");
                };
            }
        }

        if (!Objects.equals(restored.id(), entity.id())
                || restored.jobType() != entity.jobType()
                || !Objects.equals(restored.payload(), payload)
                || !payloadToMap(restored.payload()).equals(entity.payload())
                || restored.status() != entity.status()
                || restored.attemptCount() != entity.attemptCount()
                || restored.maxAttempts() != entity.maxAttempts()
                || !restored.nextEligibleAt().equals(Optional.ofNullable(
                        entity.nextEligibleAt() == null ? null
                                : instant(entity.nextEligibleAt(), entity.nextEligibleAtNanoRemainder())))
                || !restored.createdAt().equals(createdAt)
                || !restored.updatedAt().equals(updatedAt)
                || !restored.failureReason().equals(Optional.ofNullable(entity.failureReason()))
                || (entity.status() == JobStatus.RUNNING)
                != (entity.leaseExpiresAt() != null)) {
            throw new IllegalStateException("Stored job cannot be reconstructed exactly: " + entity.id());
        }
        return restored;
    }

    private static boolean validAttemptFields(AttemptRecord attempt) {
        return switch (attempt.outcome() == null ? "OPEN" : attempt.outcome()) {
            case "OPEN" -> attempt.finishedAt() == null && attempt.failureReason() == null
                    && attempt.nextEligibleAt() == null;
            case "SUCCEEDED" -> attempt.finishedAt() != null && attempt.failureReason() == null
                    && attempt.nextEligibleAt() == null;
            case "FAILED" -> attempt.finishedAt() != null && validReason(attempt.failureReason())
                    && attempt.nextEligibleAt() == null;
            case "RETRY_WAIT" -> attempt.finishedAt() != null && validReason(attempt.failureReason())
                    && attempt.nextEligibleAt() != null;
            default -> false;
        };
    }

    private static boolean validReason(String reason) {
        try {
            return Objects.equals(reason, Job.normalizeFailureReason(reason));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Map<String, Object> payloadToMap(JobPayload payload) {
        return switch (payload) {
            case ReportPayload report -> Map.of("reportName", report.reportName());
        };
    }

    private static JobPayload payloadFromMap(JobType type, Map<String, Object> fields) {
        if (type != JobType.GENERATE_REPORT || fields == null || fields.size() != 1
                || !(fields.get("reportName") instanceof String reportName)) {
            throw new IllegalStateException("Stored job payload does not match its type");
        }
        return new ReportPayload(reportName);
    }

    static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    static short remainder(Instant instant) {
        return (short) (instant.getNano() % 1_000);
    }

    static Instant instant(OffsetDateTime timestamp, short remainder) {
        Instant base = Objects.requireNonNull(timestamp, "timestamp").toInstant();
        if (remainder < 0 || remainder > 999 || base.getNano() % 1_000 != 0) {
            throw new IllegalStateException("Stored timestamp has invalid nanosecond precision");
        }
        return base.plusNanos(remainder);
    }
}
