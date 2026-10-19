package com.taskforge.persistence;

import com.taskforge.application.JobExecutionSettings;
import com.taskforge.domain.Job;
import com.taskforge.domain.JobStatus;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaJobStore {
    private final SpringDataJobRepository repository;
    private final JobMapper mapper;
    private final JdbcTemplate jdbc;
    private final JobExecutionSettings settings;
    private final EntityManager entityManager;

    JpaJobStore(SpringDataJobRepository repository, JobMapper mapper, JdbcTemplate jdbc,
                JobExecutionSettings settings, EntityManager entityManager) {
        this.repository = repository;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.settings = settings;
        this.entityManager = entityManager;
    }

    /** Creation is the sole unrestricted write; existing jobs use guarded lifecycle methods. */
    @Transactional
    public Job createQueued(Job job) {
        if (job.status() != JobStatus.QUEUED || job.attemptCount() != 0) {
            throw new IllegalArgumentException("Only a new queued job can be created");
        }
        JobEntity inserted = mapper.toEntity(job, null);
        entityManager.persist(inserted);
        entityManager.flush();
        return mapper.toDomain(inserted, List.of());
    }

    // A separate snapshot keeps the job row and its JDBC-loaded attempt history consistent.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Optional<Job> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    /** Reads and validates the job and its attempts from one PostgreSQL snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Optional<List<JobAttemptView>> findAttemptHistory(UUID id) {
        return repository.findById(id).map(row -> {
            List<AttemptRecord> attempts = attemptsFor(row.id());
            mapper.toDomain(row, attempts);
            return attempts.stream().map(JobAttemptView::from).toList();
        });
    }

    /** Commits RUNNING and its attempt before the caller executes a handler. */
    @Transactional
    public Optional<ClaimedJob> claimNextEligible() {
        Instant cutoff = databaseNow();
        Optional<JobEntity> selected = repository.findEligibleForUpdateSkipLocked(JobMapper.timestamp(cutoff));
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        JobEntity row = selected.orElseThrow();
        Instant dbNow = databaseNow();
        Job current = toDomain(row);
        if (current.status() == JobStatus.RETRY_WAIT
                && current.nextEligibleAt().orElseThrow().isAfter(dbNow)) {
            return Optional.empty();
        }
        Job running = current.start(transitionAt(current, dbNow));
        row.replaceWith(mapper.toEntity(running, dbNow.plus(settings.leaseDuration())));
        insertAttempt(running);
        repository.flush();
        return Optional.of(new ClaimedJob(running.id(), running.attemptCount(), running));
    }

    @Transactional
    public boolean renewLease(UUID id, int attemptNumber) {
        JobEntity row = repository.findByIdForUpdate(id).orElse(null);
        if (row == null) {
            return false;
        }
        Instant dbNow = databaseNow();
        if (!owns(row, attemptNumber, dbNow)) {
            return false;
        }
        Instant extended = dbNow.plus(settings.leaseDuration());
        Instant existing = row.leaseExpiresAt().toInstant();
        row.renewLease(JobMapper.timestamp(extended.isAfter(existing) ? extended : existing));
        repository.flush();
        return true;
    }

    @Transactional
    public boolean recoverOneExpired() {
        Instant cutoff = databaseNow();
        Optional<JobEntity> selected = repository.findExpiredForUpdateSkipLocked(JobMapper.timestamp(cutoff));
        if (selected.isEmpty()) {
            return false;
        }
        JobEntity row = selected.orElseThrow();
        Instant dbNow = databaseNow();
        if (row.status() != JobStatus.RUNNING || row.leaseExpiresAt().toInstant().isAfter(dbNow)) {
            return false;
        }
        Job running = toDomain(row);
        String reason = "Worker lease expired";
        Job next = failureTransition(running, true, reason, dbNow);
        closeAttempt(running.id(), running.attemptCount(), next, reason);
        row.replaceWith(mapper.toEntity(next, null));
        repository.flush();
        return true;
    }

    @Transactional
    public boolean completeSuccess(ClaimedJob claim) {
        JobEntity row = repository.findByIdForUpdate(claim.id()).orElse(null);
        if (row == null) {
            return false;
        }
        Instant dbNow = databaseNow();
        if (!owns(row, claim.attemptNumber(), dbNow)) {
            return false;
        }
        Job running = toDomain(row);
        Job succeeded = running.succeed(transitionAt(running, dbNow));
        closeAttempt(claim.id(), claim.attemptNumber(), succeeded, null);
        row.replaceWith(mapper.toEntity(succeeded, null));
        repository.flush();
        return true;
    }

    @Transactional
    public boolean completeFailure(ClaimedJob claim, boolean retryable, String safeReason) {
        JobEntity row = repository.findByIdForUpdate(claim.id()).orElse(null);
        if (row == null) {
            return false;
        }
        Instant dbNow = databaseNow();
        if (!owns(row, claim.attemptNumber(), dbNow)) {
            return false;
        }
        String normalizedReason = Job.normalizeFailureReason(safeReason);
        Job running = toDomain(row);
        Job next = failureTransition(running, retryable, normalizedReason, dbNow);
        closeAttempt(claim.id(), claim.attemptNumber(), next, normalizedReason);
        row.replaceWith(mapper.toEntity(next, null));
        repository.flush();
        return true;
    }

    private Job failureTransition(Job running, boolean retryable, String reason, Instant dbNow) {
        Instant at = transitionAt(running, dbNow);
        return retryable && running.attemptCount() < running.maxAttempts()
                ? running.scheduleRetry(settings.retryDueAt(dbNow, running.attemptCount()), at)
                : running.fail(reason, at);
    }

    private boolean owns(JobEntity row, int attemptNumber, Instant dbNow) {
        return row.status() == JobStatus.RUNNING && row.attemptCount() == attemptNumber
                && row.leaseExpiresAt() != null && row.leaseExpiresAt().toInstant().isAfter(dbNow);
    }

    private Job toDomain(JobEntity row) {
        return mapper.toDomain(row, attemptsFor(row.id()));
    }

    private List<AttemptRecord> attemptsFor(UUID id) {
        return jdbc.query("""
                SELECT job_id, attempt_number, started_at, started_at_nano_remainder,
                       finished_at, finished_at_nano_remainder, outcome, failure_reason,
                       next_eligible_at, next_eligible_at_nano_remainder, start_time_source
                FROM job_attempts WHERE job_id = ? ORDER BY attempt_number
                """, (rs, rowNumber) -> new AttemptRecord(
                (UUID) rs.getObject("job_id"), rs.getInt("attempt_number"),
                requiredInstant(rs, "started_at", "started_at_nano_remainder"),
                optionalInstant(rs, "finished_at", "finished_at_nano_remainder"),
                rs.getString("outcome"), rs.getString("failure_reason"),
                optionalInstant(rs, "next_eligible_at", "next_eligible_at_nano_remainder"),
                rs.getString("start_time_source")), id);
    }

    private void insertAttempt(Job running) {
        jdbc.update("""
                INSERT INTO job_attempts (job_id, attempt_number, started_at,
                    started_at_nano_remainder, start_time_source)
                VALUES (?, ?, ?, ?, 'RECORDED')
                """, running.id(), running.attemptCount(), JobMapper.timestamp(running.updatedAt()),
                JobMapper.remainder(running.updatedAt()));
    }

    private void closeAttempt(UUID id, int number, Job next, String reason) {
        int changed = jdbc.update("""
                UPDATE job_attempts SET finished_at = ?, finished_at_nano_remainder = ?,
                    outcome = ?, failure_reason = ?, next_eligible_at = ?,
                    next_eligible_at_nano_remainder = ?
                WHERE job_id = ? AND attempt_number = ? AND outcome IS NULL
                """, JobMapper.timestamp(next.updatedAt()), JobMapper.remainder(next.updatedAt()),
                next.status().name(), reason,
                next.nextEligibleAt().map(JobMapper::timestamp).orElse(null),
                next.nextEligibleAt().map(JobMapper::remainder).orElse((short) 0), id, number);
        if (changed != 1) {
            throw new IllegalStateException("Expected one open attempt for the owned job");
        }
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }

    private static Instant transitionAt(Job job, Instant dbNow) {
        return job.updatedAt().isAfter(dbNow) ? job.updatedAt() : dbNow;
    }

    private static Instant requiredInstant(ResultSet rs, String time, String nanos) throws SQLException {
        return JobMapper.instant(rs.getObject(time, OffsetDateTime.class), rs.getShort(nanos));
    }

    private static Instant optionalInstant(ResultSet rs, String time, String nanos) throws SQLException {
        OffsetDateTime value = rs.getObject(time, OffsetDateTime.class);
        return value == null ? null : JobMapper.instant(value, rs.getShort(nanos));
    }
}
