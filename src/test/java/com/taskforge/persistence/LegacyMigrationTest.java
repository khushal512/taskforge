package com.taskforge.persistence;

import com.taskforge.domain.Job;
import com.taskforge.domain.JobStatus;
import com.taskforge.domain.JobType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegacyMigrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Test
    void v3BackfillsLegacyRowsWithoutPresentingSyntheticStartsAsObserved() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("2").load().migrate();

        UUID queued = insertLegacy(jdbc, "QUEUED", 0, null);
        UUID running = insertLegacy(jdbc, "RUNNING", 1, null);
        UUID succeeded = insertLegacy(jdbc, "SUCCEEDED", 1, null);
        UUID failed = insertLegacy(jdbc, "FAILED", 1, "Legacy safe failure");

        Flyway.configure().dataSource(dataSource).load().migrate();
        assertThat(jdbc.queryForObject("select count(*) from jobs where max_attempts = 3", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from job_attempts", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from job_attempts where job_id = ?", Integer.class,
                queued)).isZero();
        assertThat(jdbc.queryForObject("select start_time_source from job_attempts where job_id = ?",
                String.class, running)).isEqualTo("LEGACY_DERIVED");
        assertThat(jdbc.queryForObject("select start_time_source from job_attempts where job_id = ?",
                String.class, succeeded)).isEqualTo("LEGACY_SYNTHETIC");
        assertThat(jdbc.queryForObject("select start_time_source from job_attempts where job_id = ?",
                String.class, failed)).isEqualTo("LEGACY_SYNTHETIC");
        assertThat(jdbc.queryForObject("select lease_expires_at <= clock_timestamp() from jobs where id = ?",
                Boolean.class, running)).isTrue();

        JobMapper mapper = new JobMapper();
        assertThat(restore(jdbc, mapper, queued).status()).isEqualTo(JobStatus.QUEUED);
        assertThat(restore(jdbc, mapper, running).status()).isEqualTo(JobStatus.RUNNING);
        assertThat(restore(jdbc, mapper, succeeded).status()).isEqualTo(JobStatus.SUCCEEDED);
        Job restoredFailure = restore(jdbc, mapper, failed);
        assertThat(restoredFailure.status()).isEqualTo(JobStatus.FAILED);
        assertThat(restoredFailure.failureReason()).contains("Legacy safe failure");
    }

    private static UUID insertLegacy(JdbcTemplate jdbc, String status, int attempts, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO jobs (id, job_type, payload, status, attempt_count,
                    created_at, created_at_nano_remainder, updated_at,
                    updated_at_nano_remainder, failure_reason)
                VALUES (?, 'GENERATE_REPORT', '{"reportName":"Legacy"}'::jsonb, ?, ?,
                    '2020-01-01T00:00:00Z', 123, '2020-01-01T00:00:01Z', 456, ?)
                """, id, status, attempts, reason);
        if (status.equals("QUEUED")) {
            jdbc.update("update jobs set updated_at = created_at, updated_at_nano_remainder = "
                    + "created_at_nano_remainder where id = ?", id);
        }
        return id;
    }

    private static Job restore(JdbcTemplate jdbc, JobMapper mapper, UUID id) {
        JobEntity entity = jdbc.queryForObject("select * from jobs where id = ?", (rs, index) ->
                new JobEntity(id, JobType.GENERATE_REPORT, Map.of("reportName", "Legacy"),
                        JobStatus.valueOf(rs.getString("status")), rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"), rs.getObject("next_eligible_at", OffsetDateTime.class),
                        rs.getShort("next_eligible_at_nano_remainder"),
                        rs.getObject("lease_expires_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getShort("created_at_nano_remainder"),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getShort("updated_at_nano_remainder"), rs.getString("failure_reason")), id);
        List<AttemptRecord> attempts = jdbc.query("select * from job_attempts where job_id = ?",
                (rs, index) -> new AttemptRecord(id, rs.getInt("attempt_number"),
                        JobMapper.instant(rs.getObject("started_at", OffsetDateTime.class),
                                rs.getShort("started_at_nano_remainder")),
                        rs.getObject("finished_at", OffsetDateTime.class) == null ? null
                                : JobMapper.instant(rs.getObject("finished_at", OffsetDateTime.class),
                                        rs.getShort("finished_at_nano_remainder")),
                        rs.getString("outcome"), rs.getString("failure_reason"), null,
                        rs.getString("start_time_source")), id);
        return mapper.toDomain(entity, attempts);
    }
}
