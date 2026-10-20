package com.taskforge.api;

import com.taskforge.persistence.JobResultSnapshot;

import java.util.UUID;

/** Public response containing only the stored text preview and its successful attempt. */
public record JobResultResponse(UUID jobId, int attemptNumber, String preview) {
    public static JobResultResponse from(UUID jobId, JobResultSnapshot.Result result) {
        return new JobResultResponse(jobId, result.attemptNumber(), result.preview());
    }
}
