package com.taskforge.domain;

import java.util.Objects;

public enum JobStatus {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED;

    public boolean canTransitionTo(JobStatus next) {
        Objects.requireNonNull(next, "next");

        return switch (this) {
            case QUEUED, RETRY_WAIT -> next == RUNNING;
            case RUNNING -> next == SUCCEEDED || next == FAILED || next == RETRY_WAIT;
            case SUCCEEDED, FAILED -> false;
        };
    }
}
