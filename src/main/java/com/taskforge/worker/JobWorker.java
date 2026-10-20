package com.taskforge.worker;

import com.taskforge.domain.ReportPreview;
import com.taskforge.persistence.ClaimedJob;
import com.taskforge.persistence.JpaJobStore;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class JobWorker {
    private final JpaJobStore store;
    private final JobHandlerRegistry handlers;
    private final LeaseHeartbeat heartbeat;

    JobWorker(JpaJobStore store, JobHandlerRegistry handlers, LeaseHeartbeat heartbeat) {
        this.store = store;
        this.handlers = handlers;
        this.heartbeat = heartbeat;
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
                preview = new ReportPreview(handlers.require(claim.job().jobType())
                        .execute(claim.job().payload()));
            } catch (RetryableJobException exception) {
                failureReason = "Temporary job execution failure";
                retryable = true;
            } catch (MissingJobHandlerException exception) {
                failureReason = "No handler is available for this job type";
            } catch (RuntimeException exception) {
                failureReason = "Job execution failed";
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
