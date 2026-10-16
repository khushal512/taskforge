package com.taskforge.domain;

import java.util.Objects;

public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED;

    public boolean canTransitionTo(JobStatus next) {
        Objects.requireNonNull(next, "next");

        return switch (this) {
            case QUEUED -> next == RUNNING;
            case RUNNING -> next == SUCCEEDED || next == FAILED;
            case SUCCEEDED, FAILED -> false;
        };
    }
}
