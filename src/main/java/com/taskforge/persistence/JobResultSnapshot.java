package com.taskforge.persistence;

import com.taskforge.domain.Job;
import com.taskforge.domain.JobStatus;
import com.taskforge.domain.ReportPreview;

import java.util.Objects;
import java.util.Optional;

/** Validated job state and its optional stored preview from one database snapshot. */
public record JobResultSnapshot(Job job, Optional<Result> result) {
    public JobResultSnapshot {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(result, "result");
        result.ifPresent(saved -> {
            if (job.status() != JobStatus.SUCCEEDED || saved.attemptNumber() != job.attemptCount()) {
                throw new IllegalStateException("Stored result does not match successful job attempt");
            }
        });
    }

    public record Result(int attemptNumber, String preview) {
        public Result {
            if (attemptNumber < 1) {
                throw new IllegalStateException("Stored result has an invalid attempt number");
            }
            new ReportPreview(preview);
        }
    }
}
