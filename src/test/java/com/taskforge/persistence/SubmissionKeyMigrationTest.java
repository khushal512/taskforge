package com.taskforge.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SubmissionKeyMigrationTest {
    @Container
    PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Test
    void v4PreservesExistingJobsAndEnforcesKeyAndJobUniqueness() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("3").load().migrate();

        UUID existingJob = insertQueuedJob(jdbc);
        UUID anotherJob = insertQueuedJob(jdbc);
        Flyway flyway = Flyway.configure().dataSource(dataSource).target("4").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(jdbc.queryForObject("select count(*) from jobs", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from job_submission_keys", Integer.class)).isZero();

        String keyDigest = "a".repeat(64);
        String fingerprint = "b".repeat(64);
        jdbc.update("insert into job_submission_keys (key_digest, request_fingerprint, job_id) "
                + "values (?, ?, ?)", keyDigest, fingerprint, existingJob);
        assertThatThrownBy(() -> jdbc.update("insert into job_submission_keys "
                + "(key_digest, request_fingerprint, job_id) values (?, ?, ?)",
                keyDigest, fingerprint, anotherJob)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into job_submission_keys "
                + "(key_digest, request_fingerprint, job_id) values (?, ?, ?)",
                "c".repeat(64), fingerprint, existingJob)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select job_id from job_submission_keys where key_digest = ?",
                UUID.class, keyDigest)).isEqualTo(existingJob);
        assertThat(jdbc.queryForObject("select count(*) from jobs", Integer.class)).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void v4RejectsInvalidDigestsAndMissingJobsAndRetainsKeys() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();

        UUID job = insertQueuedJob(jdbc);
        String keyDigest = "a".repeat(64);
        String fingerprint = "b".repeat(64);
        assertThatThrownBy(() -> jdbc.update("insert into job_submission_keys "
                + "(key_digest, request_fingerprint, job_id) values (?, ?, ?)",
                "invalid", fingerprint, job)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into job_submission_keys "
                + "(key_digest, request_fingerprint, job_id) values (?, ?, ?)",
                keyDigest, "invalid", job)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into job_submission_keys "
                + "(key_digest, request_fingerprint, job_id) values (?, ?, ?)",
                keyDigest, fingerprint, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("insert into job_submission_keys (key_digest, request_fingerprint, job_id) "
                + "values (?, ?, ?)", keyDigest, fingerprint, job);
        assertThatThrownBy(() -> jdbc.update("delete from jobs where id = ?", job))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from job_submission_keys", Integer.class)).isEqualTo(1);
    }

    private static UUID insertQueuedJob(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO jobs (id, job_type, payload, status, attempt_count,
                    created_at, created_at_nano_remainder, updated_at, updated_at_nano_remainder)
                VALUES (?, 'GENERATE_REPORT', '{"reportName":"Report"}'::jsonb, 'QUEUED', 0,
                    '2020-01-01T00:00:00Z', 0, '2020-01-01T00:00:00Z', 0)
                """, id);
        return id;
    }
}
