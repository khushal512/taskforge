package com.taskforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskforge.persistence.ClaimedJob;
import com.taskforge.persistence.JpaJobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "taskforge.worker.scheduling-enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class JobApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JpaJobStore store;

    @BeforeEach
    void clearJobs() {
        jdbc.update("delete from job_submission_keys");
        jdbc.update("delete from jobs");
    }

    @Test
    void keyedPostAndNormalizedReplayReturnOriginalRepresentationAndLocation() throws Exception {
        MvcResult created = postReport(" Quarterly report ", " key-1 ");
        MvcResult replay = postReport("Quarterly report", "key-1");

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode original = json.readTree(created.getResponse().getContentAsString());
        assertThat(json.readTree(replay.getResponse().getContentAsString())).isEqualTo(original);
        assertThat(created.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/jobs/" + original.get("id").asText());
        assertThat(replay.getResponse().getHeader("Location"))
                .isEqualTo(created.getResponse().getHeader("Location"));
        assertThat(original.get("payload").get("reportName").asText()).isEqualTo("Quarterly report");
        assertCounts(1, 1);
    }

    @Test
    void replayAfterCompletionReturnsOriginalIdWithCurrentJobState() throws Exception {
        MvcResult created = postReport("Report", "state-change-key");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode original = json.readTree(created.getResponse().getContentAsString());
        UUID id = UUID.fromString(original.get("id").asText());
        String location = created.getResponse().getHeader("Location");
        assertThat(original.get("status").asText()).isEqualTo("QUEUED");
        assertThat(location).isEqualTo("/api/v1/jobs/" + id);

        ClaimedJob claim = store.claimNextEligible().orElseThrow();
        assertThat(claim.id()).isEqualTo(id);
        assertThat(store.completeSuccess(claim, new com.taskforge.domain.ReportPreview("Simulated report preview"))).isTrue();

        MvcResult replay = postReport("Report", "state-change-key");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Location")).isEqualTo(location);
        JsonNode current = json.readTree(replay.getResponse().getContentAsString());
        assertThat(current.get("id").asText()).isEqualTo(id.toString());
        assertThat(current.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(current.get("attemptCount").asInt()).isEqualTo(1);
        assertThat(current).isNotEqualTo(original);
        assertCounts(1, 1);
    }

    @Test
    void conflictingKeyReturnsSafeErrorWithoutAnotherJob() throws Exception {
        postReport("Report", "sensitive-opaque-123");
        MvcResult conflict = postReport("Different", "sensitive-opaque-123");

        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        JsonNode error = json.readTree(conflict.getResponse().getContentAsString());
        assertThat(error.get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(error.get("message").asText())
                .isEqualTo("Idempotency key was used for a different job request");
        assertThat(error.get("fieldErrors").isEmpty()).isTrue();
        assertThat(conflict.getResponse().getHeader("Location")).isNull();
        assertThat(conflict.getResponse().getContentAsString())
                .doesNotContain("sensitive-opaque-123", "fingerprint", "job_submission_keys", "SQLException", "trace");
        assertCounts(1, 1);
    }

    @Test
    void invalidSuppliedKeysReturnBadRequestWithoutCreatingJobs() throws Exception {
        for (String key : List.of("   ", "internal space", "x".repeat(129))) {
            MvcResult invalid = postReport("Report", key);
            assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
            JsonNode error = json.readTree(invalid.getResponse().getContentAsString());
            assertThat(error.get("code").asText()).isEqualTo("INVALID_REQUEST");
            assertThat(error.get("message").asText()).contains("Idempotency-Key");
            assertThat(invalid.getResponse().getContentAsString()).doesNotContain(key);
        }
        assertCounts(0, 0);
    }

    @Test
    void concurrentMatchingHttpSubmissionsCreateOneJobAndReturnCreatedThenReplay() throws Exception {
        List<MvcResult> results = concurrentPosts("Report", " Report ");
        assertThat(results.stream().map(result -> result.getResponse().getStatus()))
                .containsExactlyInAnyOrder(201, 200);
        JsonNode first = json.readTree(results.get(0).getResponse().getContentAsString());
        JsonNode second = json.readTree(results.get(1).getResponse().getContentAsString());
        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(results.get(0).getResponse().getHeader("Location"))
                .isEqualTo(results.get(1).getResponse().getHeader("Location"));
        assertCounts(1, 1);
    }

    @Test
    void concurrentDifferentHttpSubmissionsCreateOneJobAndReturnConflict() throws Exception {
        List<MvcResult> results = concurrentPosts("Report", "Different");
        assertThat(results.stream().map(result -> result.getResponse().getStatus()))
                .containsExactlyInAnyOrder(201, 409);
        MvcResult conflict = results.stream().filter(result -> result.getResponse().getStatus() == 409)
                .findFirst().orElseThrow();
        assertThat(json.readTree(conflict.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(conflict.getResponse().getContentAsString()).doesNotContain("concurrent-key");
        assertCounts(1, 1);
    }

    @Test
    void postThenGetReadsTheSameJobFromPostgres() throws Exception {
        String request = "{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\" Quarterly report \"}}";
        String responseBody = mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobType").value("GENERATE_REPORT"))
                .andExpect(jsonPath("$.payload.reportName").value("Quarterly report"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode created = json.readTree(responseBody);
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(jdbc.queryForObject("select count(*) from jobs where id = ?", Integer.class, id)).isEqualTo(1);

        mvc.perform(get("/api/v1/jobs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.payload.reportName").value("Quarterly report"))
                .andExpect(jsonPath("$.createdAt").value(created.get("createdAt").asText()))
                .andExpect(jsonPath("$.updatedAt").value(created.get("updatedAt").asText()));
    }

    @Test
    void rejectsInvalidAndExtraFieldsWithoutCreatingJobs() throws Exception {
        int before = jdbc.queryForObject("select count(*) from jobs", Integer.class);

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\"   \"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['payload.reportName']").exists());
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\"Report\"},\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\"bad\\nname\"}}"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from jobs", Integer.class)).isEqualTo(before);
    }

    @Test
    void malformedJsonAndMissingJobsReturnSafeErrors() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.trace").doesNotExist());

        mvc.perform(get("/api/v1/jobs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid job ID"));

        mvc.perform(get("/api/v1/jobs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    @Test
    void historyIsEmptyAfterPostThenShowsRunningAndSuccessfulAttempt() throws Exception {
        UUID id = submitReport();
        mvc.perform(get("/api/v1/jobs/{id}/attempts", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        ClaimedJob claim = store.claimNextEligible().orElseThrow();
        assertThat(claim.id()).isEqualTo(id);
        mvc.perform(get("/api/v1/jobs/{id}/attempts", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attemptNumber").value(1))
                .andExpect(jsonPath("$[0].outcome").value("RUNNING"))
                .andExpect(jsonPath("$[0].startTimeSource").value("RECORDED"))
                .andExpect(jsonPath("$[0].finishedAt").value(nullValue()))
                .andExpect(jsonPath("$[0].failureReason").value(nullValue()))
                .andExpect(jsonPath("$[0].nextEligibleAt").value(nullValue()))
                .andExpect(jsonPath("$[0].payload").doesNotExist())
                .andExpect(jsonPath("$[0].leaseExpiresAt").doesNotExist());

        assertThat(store.completeSuccess(claim, new com.taskforge.domain.ReportPreview("Simulated report preview"))).isTrue();
        mvc.perform(get("/api/v1/jobs/{id}/attempts", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].finishedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].failureReason").value(nullValue()));
    }

    @Test
    void terminalFailureAndInvalidIdsHaveSafeResponses() throws Exception {
        UUID id = submitReport();
        ClaimedJob claim = store.claimNextEligible().orElseThrow();
        assertThat(store.completeFailure(claim, false, "Internal database detail")).isTrue();

        mvc.perform(get("/api/v1/jobs/{id}/attempts", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("FAILED"))
                .andExpect(jsonPath("$[0].failureReason").value("Job attempt failed"))
                .andExpect(jsonPath("$[0].nextEligibleAt").value(nullValue()))
                .andExpect(jsonPath("$[0].trace").doesNotExist());
        mvc.perform(get("/api/v1/jobs/{id}/attempts", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
        mvc.perform(get("/api/v1/jobs/not-a-uuid/attempts"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid job ID"));
    }

    private MvcResult postReport(String reportName, String key) throws Exception {
        return mvc.perform(post("/api/v1/jobs").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\""
                                + reportName + "\"}}"))
                .andReturn();
    }

    private List<MvcResult> concurrentPosts(String firstName, String secondName) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = callers.submit(() -> postAfterBarrier(firstName, ready, start));
            Future<MvcResult> second = callers.submit(() -> postAfterBarrier(secondName, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private MvcResult postAfterBarrier(String reportName, CountDownLatch ready,
                                       CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting to submit");
        }
        return postReport(reportName, "concurrent-key");
    }

    private void assertCounts(int jobs, int keys) {
        assertThat(jdbc.queryForObject("select count(*) from jobs", Integer.class)).isEqualTo(jobs);
        assertThat(jdbc.queryForObject("select count(*) from job_submission_keys", Integer.class)).isEqualTo(keys);
    }

    private UUID submitReport() throws Exception {
        String response = mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\"Report\"}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }
}
