package com.taskforge.application;

import com.taskforge.domain.Job;
import com.taskforge.domain.JobType;
import com.taskforge.domain.ReportPayload;
import com.taskforge.persistence.JpaJobStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
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
        if (Objects.requireNonNull(jobType, "jobType") != JobType.GENERATE_REPORT) {
            throw new InvalidJobRequestException("Unsupported job type");
        }

        ReportPayload payload;
        try {
            payload = new ReportPayload(reportName);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidJobRequestException("reportName must be one line with 1 to 100 characters");
        }

        Job job = Job.create(UUID.randomUUID(), payload, clock.instant(), settings.maxAttempts());
        return store.createQueued(job);
    }

    public Job get(UUID id) {
        return store.findById(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new JobNotFoundException(id));
    }
}
