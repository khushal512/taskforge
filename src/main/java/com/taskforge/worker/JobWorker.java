package com.taskforge.worker;

import com.taskforge.domain.Job;
import com.taskforge.persistence.JpaJobStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

@Service
public class JobWorker {

    private final JpaJobStore store;
    private final JobHandlerRegistry handlers;
    private final Clock clock;

    JobWorker(JpaJobStore store, JobHandlerRegistry handlers, Clock clock) {
        this.store = store;
        this.handlers = handlers;
        this.clock = clock;
    }

    /** Processes at most one job; returns false when the queue is empty. */
    public boolean processOne() {
        Optional<Job> claimed = store.startOldestQueuedForSingleWorker(clock.instant());
        if (claimed.isEmpty()) {
            return false;
        }

        Job running = claimed.orElseThrow();
        String failureReason = null;
        try {
            String preview = handlers.require(running.jobType()).execute(running.payload());
            if (preview == null || preview.isBlank()) {
                throw new IllegalStateException("Handler returned an empty preview");
            }
        } catch (MissingJobHandlerException exception) {
            failureReason = "No handler is available for this job type";
        } catch (RuntimeException exception) {
            failureReason = "Job execution failed";
        }
        Job terminal = failureReason == null
                ? running.succeed(clock.instant())
                : running.fail(failureReason, clock.instant());
        store.save(terminal);
        return true;
    }
}
