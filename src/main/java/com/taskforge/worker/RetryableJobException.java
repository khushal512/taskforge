package com.taskforge.worker;

/** Only handlers that deliberately throw this exception request a retry. */
class RetryableJobException extends RuntimeException {
    RetryableJobException(String message) {
        super(message);
    }
}
