package com.taskforge.persistence;

import com.taskforge.domain.Job;
import com.taskforge.domain.JobPayload;
import com.taskforge.domain.JobType;
import com.taskforge.domain.ReportPayload;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

@Component
class JobMapper {

    JobEntity toEntity(Job job) {
        Objects.requireNonNull(job, "job");
        return new JobEntity(job.id(), job.jobType(), payloadToMap(job.payload()), job.status(),
                job.attemptCount(), timestamp(job.createdAt()), remainder(job.createdAt()),
                timestamp(job.updatedAt()), remainder(job.updatedAt()), job.failureReason().orElse(null));
    }

    Job toDomain(JobEntity entity) {
        Objects.requireNonNull(entity, "entity");
        JobPayload payload = payloadFromMap(entity.jobType(), entity.payload());
        Instant createdAt = instant(entity.createdAt(), entity.createdAtNanoRemainder());
        Instant updatedAt = instant(entity.updatedAt(), entity.updatedAtNanoRemainder());

        Job queued = Job.create(entity.id(), payload, createdAt);
        Job restored = switch (Objects.requireNonNull(entity.status(), "status")) {
            case QUEUED -> queued;
            case RUNNING -> queued.start(updatedAt);
            case SUCCEEDED -> queued.start(createdAt).succeed(updatedAt);
            case FAILED -> queued.start(createdAt).fail(entity.failureReason(), updatedAt);
        };

        if (!Objects.equals(restored.id(), entity.id())
                || restored.jobType() != entity.jobType()
                || !Objects.equals(restored.payload(), payload)
                || !payloadToMap(restored.payload()).equals(entity.payload())
                || restored.status() != entity.status()
                || restored.attemptCount() != entity.attemptCount()
                || !restored.createdAt().equals(createdAt)
                || !restored.updatedAt().equals(updatedAt)
                || !restored.failureReason().equals(java.util.Optional.ofNullable(entity.failureReason()))) {
            throw new IllegalStateException("Stored job cannot be reconstructed exactly: " + entity.id());
        }
        return restored;
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

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static short remainder(Instant instant) {
        return (short) (instant.getNano() % 1_000);
    }

    private static Instant instant(OffsetDateTime timestamp, short remainder) {
        Instant base = Objects.requireNonNull(timestamp, "timestamp").toInstant();
        if (remainder < 0 || remainder > 999 || base.getNano() % 1_000 != 0) {
            throw new IllegalStateException("Stored timestamp has invalid nanosecond precision");
        }
        return base.plusNanos(remainder);
    }
}
