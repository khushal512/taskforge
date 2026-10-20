package com.taskforge.api;

import com.taskforge.domain.Job;
import com.taskforge.domain.JobStatus;
import com.taskforge.domain.JobType;
import com.taskforge.domain.ReportPayload;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        JobType jobType,
        ReportPayloadResponse payload,
        JobStatus status,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt,
        String failureReason) {

    public static JobResponse from(Job job) {
        ReportPayloadResponse payload = switch (job.payload()) {
            case ReportPayload report -> new ReportPayloadResponse(report.reportName());
        };
        return new JobResponse(job.id(), job.jobType(), payload, job.status(), job.attemptCount(),
                job.createdAt(), job.updatedAt(), job.failureReason().orElse(null));
    }

    public record ReportPayloadResponse(String reportName) {
    }
}
