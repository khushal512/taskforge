package com.taskforge.persistence;

import com.taskforge.domain.Job;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaJobStore {

    private final SpringDataJobRepository repository;
    private final JobMapper mapper;

    JpaJobStore(SpringDataJobRepository repository, JobMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public Job save(Job job) {
        JobEntity saved = repository.save(mapper.toEntity(job));
        return mapper.toDomain(saved);
    }

    @Transactional(readOnly = true)
    public Optional<Job> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    /**
     * Locks and starts one queued job. The transaction commits before a caller outside
     * a transaction can execute its handler.
     */
    @Transactional
    public Optional<Job> claimOldestQueued(Instant at) {
        return repository.findOldestQueuedForUpdateSkipLocked()
                .map(mapper::toDomain)
                .map(job -> job.start(at))
                .map(job -> repository.save(mapper.toEntity(job)))
                .map(mapper::toDomain);
    }
}
