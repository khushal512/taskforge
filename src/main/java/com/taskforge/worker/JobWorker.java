package com.taskforge.worker;

import com.taskforge.domain.ReportPreview;
import com.taskforge.observability.WorkerMetrics;
import com.taskforge.persistence.ClaimedJob;
import com.taskforge.persistence.JpaJobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JpaJobStore store;
    private final JobHandlerRegistry handlers;
    private final LeaseHeartbeat heartbeat;
    private final WorkerMetrics metrics;

    JobWorker(JpaJobStore store, JobHandlerRegistry handlers, LeaseHeartbeat heartbeat, WorkerMetrics metrics) {
        this.store = store;
        this.handlers = handlers;
        this.heartbeat = heartbeat;
        this.metrics = metrics;
    }

    /** Recovers at most one abandoned job and executes at most one eligible job. */
    public boolean processOne() {
        boolean recovered = store.recoverOneExpired();
        Optional<ClaimedJob> claimed = store.claimNextEligible();
        if (claimed.isEmpty()) {
            return recovered;
        }

        ClaimedJob claim = claimed.orElseThrow();
        try (LeaseHeartbeat.Ownership ownership = heartbeat.start(claim)) {
            String failureReason = null;
            boolean retryable = false;
            ReportPreview preview = null;
            try {
                JobHandler handler = handlers.require(claim.job().jobType());
                preview = new ReportPreview(metrics.timeHandler(() -> handler.execute(claim.job().payload())));
            } catch (RetryableJobException exception) {
                failureReason = "Temporary job execution failure";
                retryable = true;
                log.warn("event=handler_failed attempt={} failure_kind=RETRYABLE exception_type={}",
                        claim.attemptNumber(), exception.getClass().getSimpleName());
            } catch (MissingJobHandlerException exception) {
                failureReason = "No handler is available for this job type";
                log.warn("event=handler_failed attempt={} failure_kind=MISSING_HANDLER exception_type={}",
                        claim.attemptNumber(), exception.getClass().getSimpleName());
            } catch (RuntimeException exception) {
                failureReason = "Job execution failed";
                log.warn("event=handler_failed attempt={} failure_kind=PERMANENT exception_type={}",
                        claim.attemptNumber(), exception.getClass().getSimpleName());
            }

            if (ownership.active()) {
                if (failureReason == null) {
                    store.completeSuccess(claim, preview);
                } else {
                    store.completeFailure(claim, retryable, failureReason);
                }
            }
        }
        return true;
    }
}
