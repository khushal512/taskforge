package com.taskforge.persistence;

import com.taskforge.domain.Job;
import com.taskforge.domain.JobStatus;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "taskforge.worker.scheduling-enabled=false")
@Testcontainers
class RetryRecoveryIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Autowired JpaJobStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clear() {
        jdbc.update("delete from jobs");
    }

    @Test
    void retryWaitsUntilDueAndStopsAfterThreeStartedAttempts() {
        Job queued = queued(Instant.parse("2020-01-01T00:00:00Z"));
        ClaimedJob first = store.claimNextEligible().orElseThrow();
        assertThat(store.completeFailure(first, true, "Temporary job execution failure")).isTrue();
        assertThat(store.findById(queued.id()).orElseThrow().status()).isEqualTo(JobStatus.RETRY_WAIT);
        assertThat(store.findById(queued.id()).orElseThrow().attemptCount()).isEqualTo(1);
        assertThat(store.claimNextEligible()).isEmpty();

        makeRetryDue(queued.id(), 1);
        ClaimedJob second = store.claimNextEligible().orElseThrow();
        assertThat(second.attemptNumber()).isEqualTo(2);
        assertThat(store.completeFailure(second, true, "Temporary job execution failure")).isTrue();
        assertThat(store.claimNextEligible()).isEmpty();

        makeRetryDue(queued.id(), 2);
        ClaimedJob third = store.claimNextEligible().orElseThrow();
        assertThat(third.attemptNumber()).isEqualTo(3);
        assertThat(store.completeFailure(third, true, "Temporary job execution failure")).isTrue();
        Job finished = store.findById(queued.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(JobStatus.FAILED);
        assertThat(finished.attemptCount()).isEqualTo(3);
        assertThat(finished.nextEligibleAt()).isEmpty();
        assertThat(store.claimNextEligible()).isEmpty();
        assertThat(jdbc.queryForList("select outcome from job_attempts where job_id = ? order by attempt_number",
                String.class, queued.id())).containsExactly("RETRY_WAIT", "RETRY_WAIT", "FAILED");
    }

    @Test
    void expiredAttemptCannotHeartbeatOrCompleteAfterRecoveryAndNewClaim() {
        Job queued = queued(Instant.parse("2020-01-01T00:00:00Z"));
        ClaimedJob first = store.claimNextEligible().orElseThrow();
        expireLease(queued.id());

        assertThat(store.renewLease(first.id(), first.attemptNumber())).isFalse();
        assertThat(store.completeSuccess(first, new com.taskforge.domain.ReportPreview("Simulated report preview"))).isFalse();
        assertThat(store.recoverOneExpired()).isTrue();
        assertThat(store.recoverOneExpired()).isFalse();
        assertThat(store.findById(queued.id()).orElseThrow().status()).isEqualTo(JobStatus.RETRY_WAIT);

        makeRetryDue(queued.id(), 1);
        ClaimedJob second = store.claimNextEligible().orElseThrow();
        assertThat(second.attemptNumber()).isEqualTo(2);
        assertThat(store.renewLease(first.id(), first.attemptNumber())).isFalse();
        assertThat(store.completeFailure(first, false, "Stale result")).isFalse();
        assertThat(store.findById(queued.id()).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);
        assertThat(jdbc.queryForObject("select outcome from job_attempts where job_id = ? and attempt_number = 2",
                String.class, queued.id())).isNull();
        assertThat(store.completeSuccess(second, new com.taskforge.domain.ReportPreview("Simulated report preview"))).isTrue();
    }

    @Test
    void futureDomainTimestampDoesNotDelayRetryEligibility() {
        Instant future = Instant.parse("2035-01-01T00:00:00Z");
        Job queued = queued(future);
        ClaimedJob claim = store.claimNextEligible().orElseThrow();

        assertThat(store.completeFailure(claim, true, "Temporary job execution failure")).isTrue();
        Job waiting = store.findById(queued.id()).orElseThrow();
        assertThat(waiting.updatedAt()).isEqualTo(future);
        assertThat(waiting.nextEligibleAt().orElseThrow()).isBefore(future);
        assertThat(store.claimNextEligible()).isEmpty();
        makeRetryDue(queued.id(), 1);
        ClaimedJob retry = store.claimNextEligible().orElseThrow();
        assertThat(retry.job().updatedAt()).isEqualTo(future);
    }

    @Test
    void competingRecoveryCyclesRecoverOneExpiredRowOnce() throws Exception {
        Job queued = queued(Instant.parse("2020-01-01T00:00:00Z"));
        store.claimNextEligible().orElseThrow();
        expireLease(queued.id());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> one = threads.submit(() -> recoverAfterGate(ready, start));
            Future<Boolean> two = threads.submit(() -> recoverAfterGate(ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(store.findById(queued.id()).orElseThrow().status()).isEqualTo(JobStatus.RETRY_WAIT);
            assertThat(jdbc.queryForObject("select count(*) from job_attempts where job_id = ?",
                    Integer.class, queued.id())).isEqualTo(1);
        } finally {
            start.countDown();
            threads.shutdownNow();
            assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void heartbeatHoldingTheRowMakesRecoverySkipIt() throws Exception {
        Job queued = queued(Instant.parse("2020-01-01T00:00:00Z"));
        ClaimedJob claim = store.claimNextEligible().orElseThrow();
        CountDownLatch renewed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> holder = threads.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                boolean result = store.renewLease(claim.id(), claim.attemptNumber());
                renewed.countDown();
                await(release);
                return result;
            }));
            assertThat(renewed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(store.recoverOneExpired()).isFalse();
            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(store.findById(queued.id()).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);
        } finally {
            release.countDown();
            threads.shutdownNow();
            assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void recoveryHoldingTheRowMakesLaterHeartbeatLoseOwnership() throws Exception {
        Job queued = queued(Instant.parse("2020-01-01T00:00:00Z"));
        ClaimedJob claim = store.claimNextEligible().orElseThrow();
        expireLease(queued.id());
        CountDownLatch recovered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch heartbeatReady = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> holder = threads.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                boolean result = store.recoverOneExpired();
                recovered.countDown();
                await(release);
                return result;
            }));
            assertThat(recovered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> heartbeat = threads.submit(() -> {
                heartbeatReady.countDown();
                return store.renewLease(claim.id(), claim.attemptNumber());
            });
            assertThat(heartbeatReady.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(heartbeat.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(store.findById(queued.id()).orElseThrow().status()).isEqualTo(JobStatus.RETRY_WAIT);
        } finally {
            release.countDown();
            threads.shutdownNow();
            assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Job queued(Instant createdAt) {
        return store.createQueued(Job.create(UUID.randomUUID(), new ReportPayload("Report"), createdAt));
    }

    private void expireLease(UUID id) {
        jdbc.update("update jobs set lease_expires_at = clock_timestamp() - interval '1 hour' where id = ?", id);
    }

    private void makeRetryDue(UUID id, int attempt) {
        jdbc.update("update jobs set next_eligible_at = clock_timestamp() - interval '1 hour' where id = ?", id);
        jdbc.update("update job_attempts set next_eligible_at = (select next_eligible_at from jobs where id = ?) "
                + "where job_id = ? and attempt_number = ?", id, id, attempt);
    }

    private boolean recoverAfterGate(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        return store.recoverOneExpired();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for transaction gate");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for transaction gate", exception);
        }
    }
}
