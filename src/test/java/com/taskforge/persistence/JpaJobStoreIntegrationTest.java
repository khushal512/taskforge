package com.taskforge.persistence;

import com.taskforge.domain.Job;
import com.taskforge.domain.ReportPayload;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
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

    @Test
    void flywayCreatesSchemaOnce() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where table_schema = 'public' and table_name = 'jobs'", Integer.class)).isEqualTo(1);
    }

    @Test
    void savesAndLoadsEveryCurrentLifecycleStateExactly() {
        Instant createdAt = Instant.parse("2026-10-16T10:15:30.123456789Z");
        Instant later = Instant.parse("2026-10-16T10:15:31.987654321Z");
        Job queued = Job.create(UUID.randomUUID(), new ReportPayload("Quarterly report"), createdAt);
        List<Job> jobs = List.of(
                queued,
                Job.create(UUID.randomUUID(), queued.payload(), createdAt).start(later),
                Job.create(UUID.randomUUID(), queued.payload(), createdAt).start(createdAt).succeed(later),
                Job.create(UUID.randomUUID(), queued.payload(), createdAt).start(createdAt).fail("Simulated failure", later)
        );

        for (Job original : jobs) {
            Job saved = store.save(original);
            Job loaded = store.findById(original.id()).orElseThrow();
            assertSameFields(original, saved);
            assertSameFields(original, loaded);
        }
    }

    @Test
    void rejectsStoredPayloadThatWouldChangeDuringReconstruction() {
        Job job = Job.create(UUID.randomUUID(), new ReportPayload("Report"), Instant.parse("2026-10-16T10:15:30Z"));
        store.save(job);
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
    void databaseRejectsAttemptAndTimeInvariantViolations() {
        Job job = Job.create(UUID.randomUUID(), new ReportPayload("Report"), Instant.parse("2026-10-16T10:15:30.123456789Z"));
        store.save(job);

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
        assertThat(actual.createdAt()).isEqualTo(expected.createdAt());
        assertThat(actual.updatedAt()).isEqualTo(expected.updatedAt());
        assertThat(actual.failureReason()).isEqualTo(expected.failureReason());
    }
}
