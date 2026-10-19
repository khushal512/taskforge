package com.taskforge.persistence;

import com.taskforge.domain.Job;
import com.taskforge.domain.ReportPayload;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "taskforge.worker.scheduling-enabled=false")
@Testcontainers
class JpaJobStoreIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Autowired
    private JpaJobStore store;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearJobs() {
        jdbc.update("delete from jobs");
    }

    @Test
    void flywayCreatesSchemaOnce() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where table_schema = 'public' and table_name = 'jobs'", Integer.class)).isEqualTo(1);
    }

    @Test
    void savesAndLoadsLifecycleStatesWithAttemptHistory() {
        Instant createdAt = Instant.parse("2026-10-16T10:15:30.123456789Z");
        Job queued = Job.create(UUID.randomUUID(), new ReportPayload("Quarterly report"), createdAt);
        Job saved = store.createQueued(queued);
        assertSameFields(queued, saved);
        assertSameFields(queued, store.findById(queued.id()).orElseThrow());

        ClaimedJob running = store.claimNextEligible().orElseThrow();
        assertThat(running.id()).isEqualTo(queued.id());
        assertThat(running.job().attemptCount()).isEqualTo(1);
        assertSameFields(running.job(), store.findById(queued.id()).orElseThrow());

        assertThat(store.completeSuccess(running)).isTrue();
        Job succeeded = store.findById(queued.id()).orElseThrow();
        assertThat(succeeded.status()).isEqualTo(com.taskforge.domain.JobStatus.SUCCEEDED);
        assertThat(succeeded.createdAt()).isEqualTo(createdAt);
        assertThat(succeeded.payload()).isEqualTo(queued.payload());
        assertThat(jdbc.queryForObject("select count(*) from job_attempts where job_id = ?", Integer.class,
                queued.id())).isEqualTo(1);
    }

    @Test
    void rejectsStoredPayloadThatWouldChangeDuringReconstruction() {
        Job job = Job.create(UUID.randomUUID(), new ReportPayload("Report"), Instant.parse("2026-10-16T10:15:30Z"));
        store.createQueued(job);
        jdbc.update("update jobs set payload = ?::jsonb where id = ?", "{\"reportName\":\" Report \"}", job.id());

        assertThatThrownBy(() -> store.findById(job.id()))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Stored job cannot be reconstructed exactly: " + job.id());
    }

    @Test
    void returnsEmptyForUnknownId() {
        assertThat(store.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void creationCannotOverwriteAnExistingJobWithTheSameId() {
        Job queued = Job.create(UUID.randomUUID(), new ReportPayload("Original"),
                Instant.parse("2020-01-01T00:00:00Z"));
        store.createQueued(queued);

        Job duplicate = Job.create(queued.id(), new ReportPayload("Replacement"), queued.createdAt());
        assertThatThrownBy(() -> store.createQueued(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.findById(queued.id()).orElseThrow().payload()).isEqualTo(queued.payload());
    }

    @Test
    void attemptReasonIsNormalizedAndMapperRejectsAChangedStoredReason() {
        Job queued = store.createQueued(Job.create(UUID.randomUUID(), new ReportPayload("Report"),
                Instant.parse("2020-01-01T00:00:00Z")));
        ClaimedJob claim = store.claimNextEligible().orElseThrow();
        assertThat(store.completeFailure(claim, false, "  Safe failure  ")).isTrue();
        assertThat(store.findById(queued.id()).orElseThrow().failureReason()).contains("Safe failure");

        jdbc.update("update job_attempts set failure_reason = ' Safe failure ' where job_id = ?", queued.id());
        assertThatThrownBy(() -> store.findById(queued.id()))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void databaseRejectsAttemptAndTimeInvariantViolations() {
        Job job = Job.create(UUID.randomUUID(), new ReportPayload("Report"), Instant.parse("2026-10-16T10:15:30.123456789Z"));
        store.createQueued(job);

        assertThatThrownBy(() -> jdbc.update("update jobs set attempt_count = 1 where id = ?", job.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update jobs set updated_at = created_at - interval '1 second' where id = ?", job.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.findById(job.id()).orElseThrow().attemptCount()).isZero();
    }

    private static void assertSameFields(Job expected, Job actual) {
        assertThat(actual.id()).isEqualTo(expected.id());
        assertThat(actual.jobType()).isEqualTo(expected.jobType());
        assertThat(actual.payload()).isEqualTo(expected.payload());
        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.attemptCount()).isEqualTo(expected.attemptCount());
        assertThat(actual.maxAttempts()).isEqualTo(expected.maxAttempts());
        assertThat(actual.nextEligibleAt()).isEqualTo(expected.nextEligibleAt());
        assertThat(actual.createdAt()).isEqualTo(expected.createdAt());
        assertThat(actual.updatedAt()).isEqualTo(expected.updatedAt());
        assertThat(actual.failureReason()).isEqualTo(expected.failureReason());
    }
}
