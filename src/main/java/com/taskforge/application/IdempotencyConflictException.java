package com.taskforge.application;

/** A committed submission key belongs to a different logical job request. */
public final class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("Idempotency key was used for a different job request");
    }
}
