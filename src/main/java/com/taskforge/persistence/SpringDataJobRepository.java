package com.taskforge.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;
import java.time.OffsetDateTime;

interface SpringDataJobRepository extends JpaRepository<JobEntity, UUID> {
    @Query(value = """
            SELECT j.* FROM jobs AS j
            WHERE j.status = 'QUEUED'
               OR (j.status = 'RETRY_WAIT' AND j.next_eligible_at <= ?1)
            ORDER BY j.created_at, j.created_at_nano_remainder, j.id
            LIMIT 1
            FOR UPDATE OF j SKIP LOCKED
            """, nativeQuery = true)
    Optional<JobEntity> findEligibleForUpdateSkipLocked(OffsetDateTime cutoff);

    @Query(value = """
            SELECT j.* FROM jobs AS j
            WHERE j.status = 'RUNNING' AND j.lease_expires_at <= ?1
            ORDER BY j.lease_expires_at, j.id
            LIMIT 1
            FOR UPDATE OF j SKIP LOCKED
            """, nativeQuery = true)
    Optional<JobEntity> findExpiredForUpdateSkipLocked(OffsetDateTime cutoff);

    @Query(value = "SELECT j.* FROM jobs AS j WHERE j.id = ?1 FOR UPDATE OF j", nativeQuery = true)
    Optional<JobEntity> findByIdForUpdate(UUID id);
}
