package com.taskforge.application;

import com.taskforge.domain.Job;
import com.taskforge.domain.JobType;
import com.taskforge.domain.ReportPayload;
import com.taskforge.persistence.JpaJobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "taskforge.worker.scheduling-enabled=false")
@Testcontainers
class JobIdempotencyIntegrationTest {
    private static final JobType TYPE = JobType.GENERATE_REPORT;
    private static final String KEY = "key-1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Autowired JobApplicationService service;
    @Autowired JpaJobStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clearJobs() {
        jdbc.update("delete from job_submission_keys");
        jdbc.update("delete from jobs");
    }

    @Test
    void equivalentLogicalRequestAndNormalizedKeyReplayTheOriginalJob() {
        SubmissionResult first = service.submitWithOptionalKey(TYPE, " Report ", " key-1 ");
        SubmissionResult replay = service.submitWithOptionalKey(TYPE, "Report", KEY);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.job().id()).isEqualTo(first.job().id());
        assertThat(replay.job().payload()).isEqualTo(new ReportPayload("Report"));
        assertCounts(1, 1);
    }

    @Test
    void keyReusedForDifferentLogicalRequestReportsConflictWithoutAnotherJob() {
        UUID original = service.submitWithOptionalKey(TYPE, "Report", KEY).job().id();

        assertThatThrownBy(() -> service.submitWithOptionalKey(TYPE, "Different", KEY))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessage("Idempotency key was used for a different job request");
        assertThat(store.findSubmissionByKey(SubmissionIdentity.from(KEY, TYPE,
                new ReportPayload("Report")).keyDigest()).orElseThrow().job().id()).isEqualTo(original);
        assertCounts(1, 1);
    }

    @Test
    void absentKeyPreservesIndependentJobCreation() {
        SubmissionResult first = service.submitWithOptionalKey(TYPE, "Report", null);
        SubmissionResult second = service.submitWithOptionalKey(TYPE, "Report", null);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isFalse();
        assertThat(second.job().id()).isNotEqualTo(first.job().id());
        assertCounts(2, 0);
    }

    @Test
    void concurrentMatchingRequestsReturnOneJobAndOneReplay() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<SubmissionResult> first = callers.submit(() -> submitAfterBarrier(" Report ", ready, start));
            Future<SubmissionResult> second = callers.submit(() -> submitAfterBarrier("Report", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            SubmissionResult a = first.get(20, TimeUnit.SECONDS);
            SubmissionResult b = second.get(20, TimeUnit.SECONDS);
            assertThat(a.job().id()).isEqualTo(b.job().id());
            assertThat(List.of(a.replayed(), b.replayed())).containsExactlyInAnyOrder(false, true);
            assertCounts(1, 1);
        } finally {
            start.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentDifferentRequestsReturnOneJobAndOneConflict() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = callers.submit(() -> submitOrConflict("Report", ready, start));
            Future<Object> second = callers.submit(() -> submitOrConflict("Different", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(SubmissionResult.class::isInstance).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(IdempotencyConflictException.class::isInstance).count())
                    .isEqualTo(1);
            assertCounts(1, 1);
        } finally {
            start.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void inFlightWinnerCommitsBeforeLoserResolvesItsReplay() throws Exception {
        Job winner = Job.create(UUID.randomUUID(), new ReportPayload("Report"),
                Instant.parse("2020-01-01T00:00:00Z"));
        SubmissionIdentity identity = SubmissionIdentity.from(KEY, TYPE, (ReportPayload) winner.payload());
        CountDownLatch winnerInserted = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = callers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                store.createQueued(winner);
                jdbc.update("insert into job_submission_keys (key_digest, request_fingerprint, job_id) "
                        + "values (?, ?, ?)", identity.keyDigest(), identity.requestFingerprint(), winner.id());
                winnerInserted.countDown();
                await(releaseWinner);
                return null;
            }));
            assertThat(winnerInserted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<SubmissionResult> second = callers.submit(() -> service.submitWithOptionalKey(TYPE, "Report", KEY));
            assertThat(waitForBlockedKeyInsert()).isTrue();
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();
            releaseWinner.countDown();

            first.get(10, TimeUnit.SECONDS);
            SubmissionResult replay = second.get(10, TimeUnit.SECONDS);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.job().id()).isEqualTo(winner.id());
            assertCounts(1, 1);
        } finally {
            releaseWinner.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private SubmissionResult submitAfterBarrier(String reportName, CountDownLatch ready,
                                                CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting to submit");
        }
        return service.submitWithOptionalKey(TYPE, reportName, KEY);
    }

    private Object submitOrConflict(String reportName, CountDownLatch ready,
                                    CountDownLatch start) throws InterruptedException {
        try {
            return submitAfterBarrier(reportName, ready, start);
        } catch (IdempotencyConflictException conflict) {
            return conflict;
        }
    }

    private boolean waitForBlockedKeyInsert() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE pid <> pg_backend_pid()
                      AND query LIKE '%INSERT INTO job_submission_keys%'
                      AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (blocked != null && blocked > 0) {
                return true;
            }
        }
        return false;
    }

    private void assertCounts(int jobs, int keys) {
        assertThat(jdbc.queryForObject("select count(*) from jobs", Integer.class)).isEqualTo(jobs);
        assertThat(jdbc.queryForObject("select count(*) from job_submission_keys", Integer.class)).isEqualTo(keys);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to commit the key");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to commit the key", exception);
        }
    }
}
