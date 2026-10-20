package com.taskforge.api;

import com.taskforge.domain.Job;
import com.taskforge.domain.ReportPayload;
import com.taskforge.persistence.ClaimedJob;
import com.taskforge.persistence.JpaJobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "taskforge.worker.scheduling-enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class JobResultApiIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Autowired MockMvc mvc;
    @Autowired JpaJobStore store;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("delete from jobs");
    }

    @Test
    void queuedRunningAndRetryWaitJobsReportResultNotReady() throws Exception {
        UUID id = queued();
        expectUnavailable(id, "RESULT_NOT_READY", "Job result is not yet available");

        ClaimedJob claim = store.claimNextEligible().orElseThrow();
        expectUnavailable(id, "RESULT_NOT_READY", "Job result is not yet available");

        store.completeFailure(claim, true, "Temporary job execution failure");
        expectUnavailable(id, "RESULT_NOT_READY", "Job result is not yet available");
    }

    @Test
    void failedJobReportsNoSuccessfulResult() throws Exception {
        UUID id = queued();
        ClaimedJob claim = store.claimNextEligible().orElseThrow();
        store.completeFailure(claim, false, "Job execution failed");

        expectUnavailable(id, "RESULT_FAILED", "Failed job has no successful result");
    }

    @Test
    void legacySuccessWithoutResultIsNotPresentedAsPendingOrFailed() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO jobs (id, job_type, payload, status, attempt_count,
                    created_at, created_at_nano_remainder, updated_at, updated_at_nano_remainder)
                VALUES (?, 'GENERATE_REPORT', '{"reportName":"Report"}'::jsonb, 'SUCCEEDED', 1,
                    '2020-01-01T00:00:00Z', 0, '2020-01-01T00:00:01Z', 0)
                """, id);
        jdbc.update("""
                INSERT INTO job_attempts (job_id, attempt_number, started_at, started_at_nano_remainder,
                    finished_at, finished_at_nano_remainder, outcome, start_time_source)
                VALUES (?, 1, '2020-01-01T00:00:00Z', 0,
                    '2020-01-01T00:00:01Z', 0, 'SUCCEEDED', 'LEGACY_SYNTHETIC')
                """, id);

        expectUnavailable(id, "RESULT_UNAVAILABLE", "No stored result is available for this successful job");
    }

    @Test
    void unknownAndMalformedIdsKeepExistingSafeErrors() throws Exception {
        mvc.perform(get("/api/v1/jobs/{id}/result", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.trace").doesNotExist());
        mvc.perform(get("/api/v1/jobs/not-a-uuid/result"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid job ID"));
    }

    private UUID queued() {
        UUID id = UUID.randomUUID();
        store.createQueued(Job.create(id, new ReportPayload("Report"),
                Instant.parse("2020-01-01T00:00:00Z")));
        return id;
    }

    private void expectUnavailable(UUID id, String code, String message) throws Exception {
        mvc.perform(get("/api/v1/jobs/{id}/result", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.preview").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }
}
