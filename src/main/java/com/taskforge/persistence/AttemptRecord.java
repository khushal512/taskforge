package com.taskforge.persistence;

import java.time.Instant;
import java.util.UUID;

record AttemptRecord(UUID jobId, int number, Instant startedAt, Instant finishedAt,
                     String outcome, String failureReason, Instant nextEligibleAt,
                     String startTimeSource) {
}
