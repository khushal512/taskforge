package com.taskforge.worker;

import com.taskforge.domain.JobType;

final class MissingJobHandlerException extends RuntimeException {
    MissingJobHandlerException(JobType type) {
        super("No handler registered for " + type);
    }
}
