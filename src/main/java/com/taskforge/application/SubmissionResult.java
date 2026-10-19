package com.taskforge.application;

import com.taskforge.domain.Job;

import java.util.Objects;

/** The current job and whether this call reused a committed submission key. */
public record SubmissionResult(Job job, boolean replayed) {
    public SubmissionResult {
        Objects.requireNonNull(job, "job");
    }
}
