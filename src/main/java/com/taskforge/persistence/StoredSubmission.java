package com.taskforge.persistence;

import com.taskforge.domain.Job;

import java.util.Objects;

/** A committed key's request fingerprint and its current, validated job state. */
public record StoredSubmission(Job job, String requestFingerprint) {
    public StoredSubmission {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
    }
}
