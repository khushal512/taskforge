package com.taskforge.persistence;

import com.taskforge.domain.JobStatus;
import com.taskforge.domain.JobType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "jobs")
class JobEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 40)
    private JobType jobType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private JobStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_at_nano_remainder", nullable = false)
    private short createdAtNanoRemainder;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_at_nano_remainder", nullable = false)
    private short updatedAtNanoRemainder;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    protected JobEntity() {
        // JPA creates entities through this constructor.
    }

    JobEntity(UUID id, JobType jobType, Map<String, Object> payload, JobStatus status,
              int attemptCount, OffsetDateTime createdAt, short createdAtNanoRemainder,
              OffsetDateTime updatedAt, short updatedAtNanoRemainder, String failureReason) {
        this.id = id;
        this.jobType = jobType;
        this.payload = payload;
        this.status = status;
        this.attemptCount = attemptCount;
        this.createdAt = createdAt;
        this.createdAtNanoRemainder = createdAtNanoRemainder;
        this.updatedAt = updatedAt;
        this.updatedAtNanoRemainder = updatedAtNanoRemainder;
        this.failureReason = failureReason;
    }

    UUID id() { return id; }
    JobType jobType() { return jobType; }
    Map<String, Object> payload() { return payload; }
    JobStatus status() { return status; }
    int attemptCount() { return attemptCount; }
    OffsetDateTime createdAt() { return createdAt; }
    short createdAtNanoRemainder() { return createdAtNanoRemainder; }
    OffsetDateTime updatedAt() { return updatedAt; }
    short updatedAtNanoRemainder() { return updatedAtNanoRemainder; }
    String failureReason() { return failureReason; }
}
