package com.taskforge.persistence;

import com.taskforge.domain.Job;
import com.taskforge.domain.ReportPayload;
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
class KeyedSubmissionStoreIntegrationTest {
    private static final String KEY = "a".repeat(64);
    private static final String FINGERPRINT = "b".repeat(64);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Autowired JpaJobStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clearJobs() {
        jdbc.update("delete from job_submission_keys");
        jdbc.update("delete from jobs");
    }

    @Test
    void firstKeyedCreationCommitsOneJobAndKeyAndMatchingReplayFindsIt() {
        Job first = queued("Report");
        assertThat(store.createQueuedWithKey(first, KEY, FINGERPRINT).id()).isEqualTo(first.id());

        Job duplicateCandidate = queued("Report");
        assertThatThrownBy(() -> store.createQueuedWithKey(duplicateCandidate, KEY, FINGERPRINT))
                .isInstanceOf(SubmissionKeyAlreadyExistsException.class);
        StoredSubmission replay = store.findSubmissionByKey(KEY).orElseThrow();
        assertThat(replay.job().id()).isEqualTo(first.id());
        assertThat(replay.requestFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(store.findById(duplicateCandidate.id())).isEmpty();
        assertCounts(1);
    }

    @Test
    void differentFingerprintIsAvailableForConflictDecisionWithoutReplacingTheJob() {
        Job first = queued("Report");
        store.createQueuedWithKey(first, KEY, FINGERPRINT);

        assertThatThrownBy(() -> store.createQueuedWithKey(queued("Different"), KEY, "c".repeat(64)))
                .isInstanceOf(SubmissionKeyAlreadyExistsException.class);
        StoredSubmission existing = store.findSubmissionByKey(KEY).orElseThrow();
        assertThat(existing.requestFingerprint()).isNotEqualTo("c".repeat(64));
        assertThat(existing.job().id()).isEqualTo(first.id());
        assertCounts(1);
    }

    @Test
    void simultaneousMatchingCreationsResolveToOneCommittedJob() throws Exception {
        Job first = queued("Report");
        Job second = queued("Report");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> firstResult = callers.submit(() -> submitOrResolve(first, ready, start));
            Future<UUID> secondResult = callers.submit(() -> submitOrResolve(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            UUID winner = firstResult.get(20, TimeUnit.SECONDS);
            assertThat(secondResult.get(20, TimeUnit.SECONDS)).isEqualTo(winner);
            assertThat(winner).isIn(first.id(), second.id());
            assertThat(store.findSubmissionByKey(KEY).orElseThrow().job().id()).isEqualTo(winner);
            assertCounts(1);
            assertThat(jdbc.queryForObject("select count(*) from jobs where id in (?, ?)",
                    Integer.class, first.id(), second.id())).isEqualTo(1);
        } finally {
            start.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void duplicateWaitsForUncommittedKeyThenRollsBackItsProvisionalJob() throws Exception {
        Job winner = queued("Report");
        Job loser = queued("Report");
        CountDownLatch winnerInserted = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = callers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                store.createQueued(winner);
                jdbc.update("insert into job_submission_keys (key_digest, request_fingerprint, job_id) "
                        + "values (?, ?, ?)", KEY, FINGERPRINT, winner.id());
                winnerInserted.countDown();
                await(releaseWinner);
                return null;
            }));
            assertThat(winnerInserted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> second = callers.submit(() -> {
                assertThatThrownBy(() -> store.createQueuedWithKey(loser, KEY, FINGERPRINT))
                        .isInstanceOf(SubmissionKeyAlreadyExistsException.class);
            });
            assertThat(waitForBlockedKeyInsert()).isTrue();
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();
            releaseWinner.countDown();

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(store.findSubmissionByKey(KEY).orElseThrow().job().id()).isEqualTo(winner.id());
            assertThat(store.findById(loser.id())).isEmpty();
            assertCounts(1);
        } finally {
            releaseWinner.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aFailedJobInsertLeavesNoKeyAndASeparateRetryCanSucceed() {
        Job existing = queued("Existing");
        store.createQueued(existing);

        Job duplicateId = Job.create(existing.id(), new ReportPayload("New"), existing.createdAt());
        assertThatThrownBy(() -> store.createQueuedWithKey(duplicateId, KEY, FINGERPRINT))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.findSubmissionByKey(KEY)).isEmpty();
        assertThat(store.findById(existing.id()).orElseThrow().payload()).isEqualTo(existing.payload());

        Job retry = queued("New");
        assertThat(store.createQueuedWithKey(retry, KEY, FINGERPRINT).id()).isEqualTo(retry.id());
        assertThat(store.findSubmissionByKey(KEY).orElseThrow().job().id()).isEqualTo(retry.id());
        assertCounts(2);
    }

    private UUID submitOrResolve(Job candidate, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting to start concurrent submissions");
        }
        try {
            return store.createQueuedWithKey(candidate, KEY, FINGERPRINT).id();
        } catch (SubmissionKeyAlreadyExistsException collision) {
            StoredSubmission existing = store.findSubmissionByKey(KEY).orElseThrow();
            assertThat(existing.requestFingerprint()).isEqualTo(FINGERPRINT);
            return existing.job().id();
        }
    }

    private void assertCounts(int expectedJobs) {
        assertThat(jdbc.queryForObject("select count(*) from jobs", Integer.class)).isEqualTo(expectedJobs);
        assertThat(jdbc.queryForObject("select count(*) from job_submission_keys", Integer.class)).isEqualTo(1);
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

    private static Job queued(String reportName) {
        return Job.create(UUID.randomUUID(), new ReportPayload(reportName),
                Instant.parse("2020-01-01T00:00:00Z"));
    }
}
