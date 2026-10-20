package com.taskforge.persistence;

import com.taskforge.domain.Job;
import com.taskforge.domain.JobStatus;
import com.taskforge.domain.ReportPayload;
import com.taskforge.domain.ReportPreview;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "taskforge.worker.scheduling-enabled=false")
@Testcontainers
class JobResultCompletionIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Autowired JpaJobStore store;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("delete from jobs");
    }

    @Test
    void successfulCompletionStoresPreviewForOwnedAttempt() {
        Job queued = queued();
        ClaimedJob claim = store.claimNextEligible().orElseThrow();

        assertThat(store.completeSuccess(claim, new ReportPreview("Simulated report preview"))).isTrue();

        assertThat(store.findById(queued.id()).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("select attempt_number from job_results where job_id = ?", Integer.class,
                queued.id())).isEqualTo(claim.attemptNumber());
        assertThat(jdbc.queryForObject("select preview from job_results where job_id = ?", String.class,
                queued.id())).isEqualTo("Simulated report preview");
        assertThat(jdbc.queryForObject("select outcome from job_attempts where job_id = ? and attempt_number = ?",
                String.class, queued.id(), claim.attemptNumber())).isEqualTo("SUCCEEDED");
    }

    @Test
    void expiredAndSupersededClaimsCannotInsertOrReplacePreview() {
        Job queued = queued();
        ClaimedJob first = store.claimNextEligible().orElseThrow();
        jdbc.update("update jobs set lease_expires_at = clock_timestamp() - interval '1 hour' where id = ?",
                queued.id());

        assertThat(store.completeSuccess(first, new ReportPreview("stale preview"))).isFalse();
        assertThat(resultCount(queued.id())).isZero();
        assertThat(store.recoverOneExpired()).isTrue();
        jdbc.update("update jobs set next_eligible_at = clock_timestamp() - interval '1 hour' where id = ?",
                queued.id());
        jdbc.update("update job_attempts set next_eligible_at = (select next_eligible_at from jobs where id = ?) "
                + "where job_id = ? and attempt_number = 1", queued.id(), queued.id());

        ClaimedJob second = store.claimNextEligible().orElseThrow();
        assertThat(second.attemptNumber()).isEqualTo(2);
        assertThat(store.completeSuccess(first, new ReportPreview("late preview"))).isFalse();
        assertThat(resultCount(queued.id())).isZero();

        assertThat(store.completeSuccess(second, new ReportPreview("current preview"))).isTrue();
        assertThat(store.completeSuccess(first, new ReportPreview("replacement"))).isFalse();
        assertThat(jdbc.queryForObject("select preview from job_results where job_id = ?", String.class,
                queued.id())).isEqualTo("current preview");
        assertThat(jdbc.queryForObject("select attempt_number from job_results where job_id = ?", Integer.class,
                queued.id())).isEqualTo(2);
    }

    @Test
    void resultInsertFailureRollsBackAttemptClosureAndSucceededTransition() {
        Job queued = queued();
        ClaimedJob claim = store.claimNextEligible().orElseThrow();
        // Fault injection: a conflicting row causes the insert to fail after the attempt closure UPDATE.
        jdbc.update("insert into job_results (job_id, attempt_number, preview) values (?, ?, ?)",
                queued.id(), claim.attemptNumber(), "existing row");

        assertThatThrownBy(() -> store.completeSuccess(claim, new ReportPreview("new preview")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.findById(queued.id()).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);
        assertThat(jdbc.queryForObject("select outcome from job_attempts where job_id = ? and attempt_number = ?",
                String.class, queued.id(), claim.attemptNumber())).isNull();
        assertThat(jdbc.queryForObject("select finished_at from job_attempts where job_id = ? and attempt_number = ?",
                Instant.class, queued.id(), claim.attemptNumber())).isNull();
        assertThat(jdbc.queryForObject("select preview from job_results where job_id = ?", String.class,
                queued.id())).isEqualTo("existing row");

        jdbc.update("delete from job_results where job_id = ?", queued.id());
        assertThat(store.completeSuccess(claim, new ReportPreview("new preview"))).isTrue();
        assertThat(store.findById(queued.id()).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    private Job queued() {
        return store.createQueued(Job.create(UUID.randomUUID(), new ReportPayload("Quarterly report"),
                Instant.parse("2020-01-01T00:00:00Z")));
    }

    private int resultCount(UUID id) {
        return jdbc.queryForObject("select count(*) from job_results where job_id = ?", Integer.class, id);
    }
}
