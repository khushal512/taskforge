package com.taskforge.application;

public class JobResultUnavailableException extends RuntimeException {
    public enum Reason { NOT_READY, FAILED, LEGACY_MISSING }

    private final Reason reason;

    public JobResultUnavailableException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
