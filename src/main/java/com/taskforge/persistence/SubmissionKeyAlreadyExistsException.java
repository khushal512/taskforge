package com.taskforge.persistence;

/** Signals that a keyed creation rolled back; resolve the committed key in a new transaction. */
public final class SubmissionKeyAlreadyExistsException extends RuntimeException {
    public SubmissionKeyAlreadyExistsException() {
        super("Submission key already exists");
    }
}
