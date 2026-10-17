package com.taskforge.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskforge.domain.Job;
import com.taskforge.domain.JobPayload;
import com.taskforge.domain.JobStatus;
import com.taskforge.domain.JobType;
import com.taskforge.domain.ReportPayload;
import com.taskforge.persistence.JpaJobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "taskforge.worker.scheduling-enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class JobWorkerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Autowired
    private JpaJobStore store;

    @Autowired
    private JobWorker worker;

    @Autowired
    private JobHandlerRegistry registry;

    private ProbeHandler probe;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void clearJobsAndProbe() {
        jdbc.update("delete from jobs");
        probe = (ProbeHandler) registry.require(JobType.GENERATE_REPORT);
        probe.reset();
    }

    @Test
    void commitsRunningBeforeHandlerAndPersistsSuccess() {
        Job queued = saveQueued(0);

        assertThat(worker.processOne()).isTrue();

        assertThat(probe.statusSeenDuringHandler).isEqualTo("RUNNING");
        assertThat(probe.attemptsSeenDuringHandler).isEqualTo(1);
        assertThat(probe.transactionActiveDuringHandler).isFalse();
        Job finished = store.findById(queued.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(finished.attemptCount()).isEqualTo(1);
        assertThat(finished.createdAt()).isEqualTo(queued.createdAt());
        assertThat(finished.updatedAt()).isAfterOrEqualTo(queued.updatedAt());
        assertThat(finished.failureReason()).isEmpty();
        assertThat(worker.processOne()).isFalse();
    }

    @Test
    void postOnlyQueuesAndLaterWorkerCycleCompletesTheJob() throws Exception {
        String body = mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\"Quarterly report\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).get("id").asText());

        assertThat(store.findById(id).orElseThrow().status()).isEqualTo(JobStatus.QUEUED);
        assertThat(worker.processOne()).isTrue();

        mvc.perform(get("/api/v1/jobs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.attemptCount").value(1));
    }

    @Test
    void handlerFailureIsPersistedAndNextCycleStillProcessesAnotherJob() {
        Job first = saveQueued(0);
        Job second = saveQueued(1);
        probe.failNext = true;

        assertThat(worker.processOne()).isTrue();
        Job failed = store.findById(first.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.attemptCount()).isEqualTo(1);
        assertThat(failed.failureReason()).contains("Job execution failed");
        assertThat(failed.failureReason().orElseThrow()).doesNotContain("secret handler detail");

        assertThat(worker.processOne()).isTrue();
        assertThat(store.findById(second.id()).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(probe.calls).isEqualTo(2);
    }

    @Test
    void missingHandlerFailsJobWithoutExecutingArbitraryCode() {
        Job queued = saveQueued(0);
        JobWorker withoutHandlers = new JobWorker(store, new JobHandlerRegistry(List.of()), Clock.systemUTC());

        assertThat(withoutHandlers.processOne()).isTrue();

        Job failed = store.findById(queued.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.failureReason()).contains("No handler is available for this job type");
        assertThat(probe.calls).isZero();
    }

    private Job saveQueued(int secondOffset) {
        Instant createdAt = Instant.parse("2020-01-01T00:00:00Z").plusSeconds(secondOffset);
        return store.save(Job.create(UUID.randomUUID(), new ReportPayload("Quarterly report"), createdAt));
    }

    @TestConfiguration
    static class WorkerTestConfiguration {
        @Bean
        @Primary
        JobHandlerRegistry testHandlerRegistry(JdbcTemplate jdbc) {
            return new JobHandlerRegistry(List.of(new ProbeHandler(jdbc)));
        }
    }

    static class ProbeHandler implements JobHandler {
        private final JdbcTemplate jdbc;
        String statusSeenDuringHandler;
        Integer attemptsSeenDuringHandler;
        boolean transactionActiveDuringHandler;
        boolean failNext;
        int calls;

        ProbeHandler(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        void reset() {
            statusSeenDuringHandler = null;
            attemptsSeenDuringHandler = null;
            transactionActiveDuringHandler = false;
            failNext = false;
            calls = 0;
        }

        @Override
        public JobType jobType() {
            return JobType.GENERATE_REPORT;
        }

        @Override
        public String execute(JobPayload payload) {
            calls++;
            transactionActiveDuringHandler = TransactionSynchronizationManager.isActualTransactionActive();
            // This separate JDBC read sees RUNNING only after the claim transaction commits.
            UUID id = jdbc.queryForObject("select id from jobs where status = 'RUNNING'", UUID.class);
            statusSeenDuringHandler = jdbc.queryForObject("select status from jobs where id = ?", String.class, id);
            attemptsSeenDuringHandler = jdbc.queryForObject("select attempt_count from jobs where id = ?", Integer.class, id);
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("secret handler detail");
            }
            return "Simulated report preview";
        }
    }
}
