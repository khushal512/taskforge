package com.taskforge.application;

import com.taskforge.domain.Job;
import com.taskforge.domain.JobType;
import com.taskforge.domain.ReportPayload;
import com.taskforge.persistence.JpaJobStore;
import com.taskforge.persistence.JobAttemptView;
import com.taskforge.persistence.StoredSubmission;
import com.taskforge.persistence.SubmissionKeyAlreadyExistsException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class JobApplicationService {

    private final JpaJobStore store;
    private final Clock clock;
    private final JobExecutionSettings settings;

    JobApplicationService(JpaJobStore store, Clock clock, JobExecutionSettings settings) {
        this.store = store;
        this.clock = clock;
        this.settings = settings;
    }

    public Job submit(JobType jobType, String reportName) {
        return submitWithOptionalKey(jobType, reportName, null).job();
    }

    /** Coordinates separate store transactions; this method must not wrap them in one transaction. */
    public SubmissionResult submitWithOptionalKey(JobType jobType, String reportName, String rawKey) {
        if (Objects.requireNonNull(jobType, "jobType") != JobType.GENERATE_REPORT) {
            throw new InvalidJobRequestException("Unsupported job type");
        }

        ReportPayload payload;
        try {
            payload = new ReportPayload(reportName);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidJobRequestException("reportName must be one line with 1 to 100 characters");
        }

        SubmissionIdentity identity = rawKey == null ? null : SubmissionIdentity.from(rawKey, jobType, payload);
        Job job = Job.create(UUID.randomUUID(), payload, clock.instant(), settings.maxAttempts());
        if (identity == null) {
            return new SubmissionResult(store.createQueued(job), false);
        }

        try {
            return new SubmissionResult(store.createQueuedWithKey(
                    job, identity.keyDigest(), identity.requestFingerprint()), false);
        } catch (SubmissionKeyAlreadyExistsException collision) {
            // The keyed creation has rolled back through the store proxy before this fresh read.
            StoredSubmission existing = store.findSubmissionByKey(identity.keyDigest())
                    .orElseThrow(() -> new IllegalStateException("Committed submission key is missing"));
            if (!existing.requestFingerprint().equals(identity.requestFingerprint())) {
                throw new IdempotencyConflictException();
            }
            return new SubmissionResult(existing.job(), true);
        }
    }

    public Job get(UUID id) {
        return store.findById(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new JobNotFoundException(id));
    }

    public List<JobAttemptView> getAttempts(UUID id) {
        return store.findAttemptHistory(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new JobNotFoundException(id));
    }
}
