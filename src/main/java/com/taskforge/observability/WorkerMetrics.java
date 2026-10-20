package com.taskforge.observability;

import com.taskforge.domain.JobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Process-local observations of committed worker changes and handler execution. */
@Component
public class WorkerMetrics {
    private final Counter claims;
    private final Counter recoveries;
    private final Counter ownershipLosses;
    private final Counter succeeded;
    private final Counter failed;
    private final Counter retryWait;
    private final Timer handlerDuration;

    public WorkerMetrics(MeterRegistry registry) {
        claims = registry.counter("taskforge.worker.claims");
        recoveries = registry.counter("taskforge.worker.recoveries");
        ownershipLosses = registry.counter("taskforge.worker.ownership.losses");
        succeeded = registry.counter("taskforge.worker.completions", "outcome", "SUCCEEDED");
        failed = registry.counter("taskforge.worker.completions", "outcome", "FAILED");
        retryWait = registry.counter("taskforge.worker.completions", "outcome", "RETRY_WAIT");
        handlerDuration = registry.timer("taskforge.worker.handler.duration");
    }

    public void claimCommitted() { claims.increment(); }

    public void recoveryCommitted() { recoveries.increment(); }

    public void completionCommitted(JobStatus outcome) {
        switch (Objects.requireNonNull(outcome, "outcome")) {
            case SUCCEEDED -> succeeded.increment();
            case FAILED -> failed.increment();
            case RETRY_WAIT -> retryWait.increment();
            default -> throw new IllegalArgumentException("Not a completed attempt outcome: " + outcome);
        }
    }

    /** Count only a confirmed rejection of a lease renewal, not an unknown database error. */
    public void ownershipLost() { ownershipLosses.increment(); }

    public <T> T timeHandler(Supplier<T> handler) {
        long started = System.nanoTime();
        try {
            return handler.get();
        } finally {
            handlerDuration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }
}
