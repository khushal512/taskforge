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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "taskforge.worker.scheduling-enabled=false")
@Testcontainers
class ConcurrentJobClaimIntegrationTest {

    private static final Instant CLAIM_TIME = Instant.parse("2021-01-01T00:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Autowired
    private JpaJobStore store;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearJobs() {
        jdbc.update("delete from jobs");
    }

    @Test
    void twoClaimersCannotClaimTheSameJob() throws Exception {
        Job queued = saveQueued(0);

        List<Optional<Job>> results = claimTogether();

        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(results).filteredOn(Optional::isEmpty).hasSize(1);
        assertThat(results.stream().flatMap(Optional::stream).map(Job::id)).containsExactly(queued.id());
        assertClaimedOnce(queued.id());
    }

    @Test
    void twoClaimersTakeDistinctJobs() throws Exception {
        Job first = saveQueued(0);
        Job second = saveQueued(1);

        List<Optional<Job>> results = claimTogether();

        assertThat(results).allMatch(Optional::isPresent);
        assertThat(results.stream().flatMap(Optional::stream).map(Job::id))
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertClaimedOnce(first.id());
        assertClaimedOnce(second.id());
    }

    @Test
    void skipsHeldRowAndRollbackMakesItClaimableAgain() throws Exception {
        Job oldest = saveQueued(0);
        Job next = saveQueued(1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> holder = threads.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                UUID lockedId = store.claimOldestQueued(CLAIM_TIME).orElseThrow().id();
                locked.countDown();
                await(release);
                status.setRollbackOnly();
                return lockedId;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Optional<Job>> other = threads.submit(() -> store.claimOldestQueued(CLAIM_TIME));
            // The second claim must finish while the oldest row is still locked.
            assertThat(other.get(10, TimeUnit.SECONDS)).get().extracting(Job::id).isEqualTo(next.id());
            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo(oldest.id());

            assertThat(store.findById(oldest.id()).orElseThrow().status()).isEqualTo(JobStatus.QUEUED);
            assertThat(store.claimOldestQueued(CLAIM_TIME)).get().extracting(Job::id).isEqualTo(oldest.id());
            assertClaimedOnce(oldest.id());
            assertClaimedOnce(next.id());
        } finally {
            release.countDown();
            threads.shutdownNow();
            assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<Optional<Job>> claimTogether() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Job>> first = threads.submit(() -> claimAfterGate(ready, start));
            Future<Optional<Job>> second = threads.submit(() -> claimAfterGate(ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            threads.shutdownNow();
            assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Optional<Job> claimAfterGate(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        return store.claimOldestQueued(CLAIM_TIME);
    }

    private Job saveQueued(int secondOffset) {
        Instant createdAt = Instant.parse("2020-01-01T00:00:00Z").plusSeconds(secondOffset);
        return store.save(Job.create(UUID.randomUUID(), new ReportPayload("Report"), createdAt));
    }

    private void assertClaimedOnce(UUID id) {
        Job job = store.findById(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.attemptCount()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test transaction gate");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test transaction gate interrupted", exception);
        }
    }
}
