package com.taskforge.api;

import com.taskforge.persistence.JobAttemptView;

import java.time.Instant;

/** Public HTTP shape for one validated execution attempt. */
public record JobAttemptResponse(int attemptNumber, Instant startedAt, Instant finishedAt,
                                 JobAttemptView.StartTimeSource startTimeSource,
                                 JobAttemptView.Outcome outcome, String failureReason,
                                 Instant nextEligibleAt) {

    public static JobAttemptResponse from(JobAttemptView attempt) {
        return new JobAttemptResponse(attempt.attemptNumber(), attempt.startedAt(), attempt.finishedAt(),
                attempt.startTimeSource(), attempt.outcome(), attempt.failureReason(),
                attempt.nextEligibleAt());
    }
}
