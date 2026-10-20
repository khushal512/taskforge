package com.taskforge.api;

import com.taskforge.application.JobApplicationService;
import com.taskforge.application.JobNotFoundException;
import com.taskforge.persistence.JobAttemptView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JobController.class)
class JobAttemptControllerTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean JobApplicationService service;

    @Test
    void emptyHistoryIsAnEmptyArray() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getAttempts(id)).thenReturn(List.of());

        mvc.perform(get("/api/v1/jobs/{id}/attempts", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void serializesRetryThenSuccessInOrderWithNullOptionalFieldsAndNoInternalData() throws Exception {
        UUID id = UUID.randomUUID();
        Instant finished = START.plusSeconds(1);
        Instant due = START.plusSeconds(6);
        when(service.getAttempts(id)).thenReturn(List.of(
                new JobAttemptView(1, START, finished, JobAttemptView.StartTimeSource.RECORDED,
                        JobAttemptView.Outcome.RETRY_WAIT, "Temporary job execution failure", due),
                new JobAttemptView(2, due, due.plusSeconds(1), JobAttemptView.StartTimeSource.RECORDED,
                        JobAttemptView.Outcome.SUCCEEDED, null, null)));

        mvc.perform(get("/api/v1/jobs/{id}/attempts", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attemptNumber").value(1))
                .andExpect(jsonPath("$[0].outcome").value("RETRY_WAIT"))
                .andExpect(jsonPath("$[0].nextEligibleAt").value(due.toString()))
                .andExpect(jsonPath("$[1].attemptNumber").value(2))
                .andExpect(jsonPath("$[1].outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$[1].failureReason").value(nullValue()))
                .andExpect(jsonPath("$[1].nextEligibleAt").value(nullValue()))
                .andExpect(jsonPath("$[0].payload").doesNotExist())
                .andExpect(jsonPath("$[0].leaseExpiresAt").doesNotExist());
    }

    @Test
    void serializesRunningAndTerminalFailureForSeparateJobs() throws Exception {
        UUID runningId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        when(service.getAttempts(runningId)).thenReturn(List.of(new JobAttemptView(1, START, null,
                JobAttemptView.StartTimeSource.LEGACY_DERIVED,
                JobAttemptView.Outcome.RUNNING, null, null)));
        when(service.getAttempts(failedId)).thenReturn(List.of(new JobAttemptView(1, START,
                START.plusSeconds(1), JobAttemptView.StartTimeSource.LEGACY_SYNTHETIC,
                JobAttemptView.Outcome.FAILED, "Job attempt failed", null)));

        mvc.perform(get("/api/v1/jobs/{id}/attempts", runningId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("RUNNING"))
                .andExpect(jsonPath("$[0].startTimeSource").value("LEGACY_DERIVED"))
                .andExpect(jsonPath("$[0].finishedAt").value(nullValue()))
                .andExpect(jsonPath("$[0].failureReason").value(nullValue()));
        mvc.perform(get("/api/v1/jobs/{id}/attempts", failedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("FAILED"))
                .andExpect(jsonPath("$[0].startTimeSource").value("LEGACY_SYNTHETIC"))
                .andExpect(jsonPath("$[0].failureReason").value("Job attempt failed"));
    }

    @Test
    void missingAndMalformedIdsUseExistingSafeErrors() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getAttempts(id)).thenThrow(new JobNotFoundException(id));

        mvc.perform(get("/api/v1/jobs/{id}/attempts", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
        mvc.perform(get("/api/v1/jobs/not-a-uuid/attempts"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid job ID"));
    }

    @Test
    void unexpectedFailureDoesNotExposeDatabaseDetails() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getAttempts(id)).thenThrow(new IllegalStateException("private database detail"));

        mvc.perform(get("/api/v1/jobs/{id}/attempts", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(not(containsString("database detail"))));
    }
}
