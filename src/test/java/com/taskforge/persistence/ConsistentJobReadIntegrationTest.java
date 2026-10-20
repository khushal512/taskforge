package com.taskforge.persistence;

import com.taskforge.domain.Job;
import com.taskforge.domain.JobStatus;
import com.taskforge.domain.ReportPayload;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "taskforge.worker.scheduling-enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class ConsistentJobReadIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Autowired JpaJobStore store;
    @MockitoSpyBean SpringDataJobRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;

    @BeforeEach
    void clearJobs() {
        jdbc.update("delete from jobs");
    }

    @Test
    void readUsesOneSnapshotEvenWhenCalledInsideReadCommittedTransaction() throws Exception {
        ClaimedJob claim = claimJob();
        CountDownLatch rowRead = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);
        AtomicReference<String> isolation = new AtomicReference<>();
        pauseAfterJobRowRead(claim.id(), rowRead, continueRead, isolation);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<Job> result = reader.submit(() -> {
                TransactionTemplate outer = new TransactionTemplate(transactions);
                outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                return outer.execute(status -> store.findById(claim.id()).orElseThrow());
            });
            assertThat(rowRead.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(isolation.get()).isEqualTo("repeatable read");
            assertThat(store.completeSuccess(claim, new com.taskforge.domain.ReportPreview("Simulated report preview"))).isTrue();
            continueRead.countDown();

            // The job row and history both come from the pre-completion snapshot.
            assertThat(result.get(10, TimeUnit.SECONDS).status()).isEqualTo(JobStatus.RUNNING);
            assertThat(store.findById(claim.id()).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        } finally {
            continueRead.countDown();
            reader.shutdownNow();
            assertThat(reader.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void getReturnsValidSnapshotWhenCompletionCommitsBetweenItsTwoReads() throws Exception {
        ClaimedJob claim = claimJob();
        CountDownLatch rowRead = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);
        pauseAfterJobRowRead(claim.id(), rowRead, continueRead, new AtomicReference<>());
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = reader.submit(() -> {
                try {
                    mvc.perform(get("/api/v1/jobs/{id}", claim.id()))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("RUNNING"));
                } catch (Exception exception) {
                    throw new AssertionError("GET should return a consistent job snapshot", exception);
                }
            });
            assertThat(rowRead.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(store.completeSuccess(claim, new com.taskforge.domain.ReportPreview("Simulated report preview"))).isTrue();
            continueRead.countDown();
            result.get(10, TimeUnit.SECONDS);
        } finally {
            continueRead.countDown();
            reader.shutdownNow();
            assertThat(reader.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void resultGetUsesOneSnapshotWhenCompletionCommitsDuringRead() throws Exception {
        ClaimedJob claim = claimJob();
        CountDownLatch rowRead = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);
        AtomicReference<String> isolation = new AtomicReference<>();
        pauseAfterJobRowRead(claim.id(), rowRead, continueRead, isolation);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = reader.submit(() -> {
                try {
                    mvc.perform(get("/api/v1/jobs/{id}/result", claim.id()))
                            .andExpect(status().isConflict())
                            .andExpect(jsonPath("$.code").value("RESULT_NOT_READY"));
                } catch (Exception exception) {
                    throw new AssertionError("GET should use one result snapshot", exception);
                }
            });
            assertThat(rowRead.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(isolation.get()).isEqualTo("repeatable read");
            assertThat(store.completeSuccess(claim, new com.taskforge.domain.ReportPreview("Simulated report preview")))
                    .isTrue();
            continueRead.countDown();
            result.get(10, TimeUnit.SECONDS);

            mvc.perform(get("/api/v1/jobs/{id}/result", claim.id()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.preview").value("Simulated report preview"));
        } finally {
            continueRead.countDown();
            reader.shutdownNow();
            assertThat(reader.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ClaimedJob claimJob() {
        Job queued = Job.create(UUID.randomUUID(), new ReportPayload("Report"),
                Instant.parse("2020-01-01T00:00:00Z"));
        store.createQueued(queued);
        return store.claimNextEligible().orElseThrow();
    }

    private void pauseAfterJobRowRead(UUID id, CountDownLatch rowRead, CountDownLatch continueRead,
                                      AtomicReference<String> isolation) {
        doAnswer(invocation -> {
            // Read through the same JPA EntityManager as the repository, then hold the result.
            Optional<JobEntity> row = Optional.ofNullable(entityManager.find(JobEntity.class, id));
            isolation.set(jdbc.queryForObject("show transaction_isolation", String.class));
            rowRead.countDown();
            if (!continueRead.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to continue snapshot read");
            }
            return row;
        }).when(repository).findById(id);
    }
}
