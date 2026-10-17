package com.taskforge.persistence;

import com.taskforge.domain.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataJobRepository extends JpaRepository<JobEntity, UUID> {
    Optional<JobEntity> findFirstByStatusOrderByCreatedAtAscCreatedAtNanoRemainderAscIdAsc(JobStatus status);
}
