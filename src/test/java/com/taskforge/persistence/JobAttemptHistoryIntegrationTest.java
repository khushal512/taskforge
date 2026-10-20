package com.taskforge.persistence;

import com.taskforge.domain.Job;
import com.taskforge.domain.ReportPayload;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
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

@SpringBootTest(properties = "taskforge.worker.scheduling-enabled=false")
@Testcontainers
class JobAttemptHistoryIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Autowired JpaJobStore store;
    @MockitoSpyBean SpringDataJobRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clearJobs() {
        jdbc.update("delete from jobs");
    }

    @Test
    void existingQueuedJobHasEmptyHistoryButUnknownJobDoesNot() {
        Job queued = queued();

        assertThat(store.findAttemptHistory(queued.id())).contains(List.of());
        assertThat(store.findAttemptHistory(UUID.randomUUID())).isEmpty();
    }

    @Test
    void returnsRecordedAttemptsInOrderWithRetryDetails() {
        Job queued = queued();
        ClaimedJob first = store.claimNextEligible().orElseThrow();
        assertThat(store.completeFailure(first, true, "Temporary job execution failure")).isTrue();
        JobAttemptView waiting = store.findAttemptHistory(queued.id()).orElseThrow().getFirst();
        assertThat(waiting.outcome()).isEqualTo(JobAttemptView.Outcome.RETRY_WAIT);
        assertThat(waiting.failureReason()).isEqualTo("Temporary job execution failure");
        assertThat(waiting.nextEligibleAt()).isNotNull();
        assertThat(waiting.finishedAt()).isNotNull();

        makeRetryDue(queued.id());
        ClaimedJob second = store.claimNextEligible().orElseThrow();
        assertThat(store.completeSuccess(second, new com.taskforge.domain.ReportPreview("Simulated report preview"))).isTrue();

        List<JobAttemptView> history = store.findAttemptHistory(queued.id()).orElseThrow();
        assertThat(history).extracting(JobAttemptView::attemptNumber).containsExactly(1, 2);
        assertThat(history).extracting(JobAttemptView::startTimeSource)
                .containsExactly(JobAttemptView.StartTimeSource.RECORDED,
                        JobAttemptView.StartTimeSource.RECORDED);
        assertThat(history).extracting(JobAttemptView::outcome)
                .containsExactly(JobAttemptView.Outcome.RETRY_WAIT, JobAttemptView.Outcome.SUCCEEDED);
        assertThat(history.get(1).failureReason()).isNull();
        assertThat(history.get(1).nextEligibleAt()).isNull();
    }

    @Test
    void distinguishesLegacyDerivedAndSyntheticStarts() {
        Job runningJob = queued();
        ClaimedJob running = store.claimNextEligible().orElseThrow();
        jdbc.update("update job_attempts set start_time_source = 'LEGACY_DERIVED' where job_id = ?",
                runningJob.id());
        assertThat(store.findAttemptHistory(runningJob.id()).orElseThrow().getFirst().startTimeSource())
                .isEqualTo(JobAttemptView.StartTimeSource.LEGACY_DERIVED);

        assertThat(store.completeSuccess(running, new com.taskforge.domain.ReportPreview("Simulated report preview"))).isTrue();
        jdbc.update("""
                update job_attempts set started_at = ?, started_at_nano_remainder = ?,
                    start_time_source = 'LEGACY_SYNTHETIC' where job_id = ?
                """, JobMapper.timestamp(runningJob.createdAt()),
                JobMapper.remainder(runningJob.createdAt()), runningJob.id());
        JobAttemptView synthetic = store.findAttemptHistory(runningJob.id()).orElseThrow().getFirst();
        assertThat(synthetic.startTimeSource()).isEqualTo(JobAttemptView.StartTimeSource.LEGACY_SYNTHETIC);
        assertThat(synthetic.startedAt()).isEqualTo(runningJob.createdAt());
    }

    @Test
    void unfamiliarStoredFailureTextIsNotReturnedToApplicationCallers() {
        Job queued = queued();
        ClaimedJob claim = store.claimNextEligible().orElseThrow();
        assertThat(store.completeFailure(claim, false, "Internal database detail")).isTrue();

        JobAttemptView attempt = store.findAttemptHistory(queued.id()).orElseThrow().getFirst();
        assertThat(attempt.outcome()).isEqualTo(JobAttemptView.Outcome.FAILED);
        assertThat(attempt.failureReason()).isEqualTo("Job attempt failed");
    }

    @Test
    void completionBetweenRowAndAttemptQueriesUsesOneRepeatableSnapshot() throws Exception {
        Job queued = queued();
        ClaimedJob claim = store.claimNextEligible().orElseThrow();
        CountDownLatch rowRead = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);
        AtomicReference<String> isolation = new AtomicReference<>();
        doAnswer(invocation -> {
            Optional<JobEntity> row = Optional.ofNullable(entityManager.find(JobEntity.class, queued.id()));
            isolation.set(jdbc.queryForObject("show transaction_isolation", String.class));
            rowRead.countDown();
            if (!continueRead.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to read attempt history");
            }
            return row;
        }).when(repository).findById(queued.id());

        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<List<JobAttemptView>> result = reader.submit(() -> {
                TransactionTemplate outer = new TransactionTemplate(transactions);
                outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                return outer.execute(status -> store.findAttemptHistory(queued.id()).orElseThrow());
            });
            assertThat(rowRead.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(isolation.get()).isEqualTo("repeatable read");
            assertThat(store.completeSuccess(claim, new com.taskforge.domain.ReportPreview("Simulated report preview"))).isTrue();
            continueRead.countDown();

            assertThat(result.get(10, TimeUnit.SECONDS)).singleElement().satisfies(attempt -> {
                assertThat(attempt.outcome()).isEqualTo(JobAttemptView.Outcome.RUNNING);
                assertThat(attempt.finishedAt()).isNull();
            });
            assertThat(jdbc.queryForObject("select outcome from job_attempts where job_id = ?",
                    String.class, queued.id())).isEqualTo("SUCCEEDED");
        } finally {
            continueRead.countDown();
            reader.shutdownNow();
            assertThat(reader.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Job queued() {
        return store.createQueued(Job.create(UUID.randomUUID(), new ReportPayload("Report"),
                Instant.parse("2020-01-01T00:00:00.123456789Z")));
    }

    private void makeRetryDue(UUID id) {
        jdbc.update("update jobs set next_eligible_at = clock_timestamp() - interval '1 hour' where id = ?", id);
        jdbc.update("update job_attempts set next_eligible_at = (select next_eligible_at from jobs where id = ?) "
                + "where job_id = ? and attempt_number = 1", id, id);
    }
}
