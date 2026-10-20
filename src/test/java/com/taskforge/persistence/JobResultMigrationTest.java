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
class JobResultMigrationTest {
    @Container
    PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Test
    void v5LeavesExistingSuccessWithoutAnInventedResult() {
        JdbcTemplate jdbc = jdbc();
        Flyway.configure().dataSource(jdbc.getDataSource()).target("4").load().migrate();
        UUID legacySuccess = insertSucceededJob(jdbc);

        Flyway flyway = Flyway.configure().dataSource(jdbc.getDataSource()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
        assertThat(jdbc.queryForObject("select count(*) from job_results", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select status from jobs where id = ?", String.class, legacySuccess))
                .isEqualTo("SUCCEEDED");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void v5BoundsPreviewAndTiesOneResultToAnExistingAttempt() {
        JdbcTemplate jdbc = jdbc();
        Flyway.configure().dataSource(jdbc.getDataSource()).load().migrate();
        UUID success = insertSucceededJob(jdbc);
        UUID queued = insertQueuedJob(jdbc);

        assertThatThrownBy(() -> insertResult(jdbc, success, " \n\t "))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertResult(jdbc, success, "x".repeat(2049)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertResult(jdbc, queued, "No attempt exists"))
                .isInstanceOf(DataIntegrityViolationException.class);

        String preview = "# Report\n\nSimulated report preview.";
        insertResult(jdbc, success, preview);
        assertThatThrownBy(() -> insertResult(jdbc, success, "Another result"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select preview from job_results where job_id = ?",
                String.class, success)).isEqualTo(preview);
        assertThat(jdbc.queryForObject("select attempt_number from job_results where job_id = ?",
                Integer.class, success)).isEqualTo(1);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    private static UUID insertSucceededJob(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO jobs (id, job_type, payload, status, attempt_count,
                    created_at, created_at_nano_remainder, updated_at, updated_at_nano_remainder)
                VALUES (?, 'GENERATE_REPORT', '{"reportName":"Report"}'::jsonb, 'SUCCEEDED', 1,
                    '2020-01-01T00:00:00Z', 0, '2020-01-01T00:00:01Z', 0)
                """, id);
        jdbc.update("""
                INSERT INTO job_attempts (job_id, attempt_number, started_at, started_at_nano_remainder,
                    finished_at, finished_at_nano_remainder, outcome, start_time_source)
                VALUES (?, 1, '2020-01-01T00:00:00Z', 0,
                    '2020-01-01T00:00:01Z', 0, 'SUCCEEDED', 'LEGACY_SYNTHETIC')
                """, id);
        return id;
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

    private static void insertResult(JdbcTemplate jdbc, UUID id, String preview) {
        jdbc.update("INSERT INTO job_results (job_id, attempt_number, preview) VALUES (?, 1, ?)", id, preview);
    }
}
