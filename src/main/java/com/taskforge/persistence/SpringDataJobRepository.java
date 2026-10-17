package com.taskforge.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface SpringDataJobRepository extends JpaRepository<JobEntity, UUID> {
    @Query(value = """
            SELECT j.* FROM jobs AS j
            WHERE j.status = 'QUEUED'
            ORDER BY j.created_at, j.created_at_nano_remainder, j.id
            LIMIT 1
            FOR UPDATE OF j SKIP LOCKED
            """, nativeQuery = true)
    Optional<JobEntity> findOldestQueuedForUpdateSkipLocked();
}
