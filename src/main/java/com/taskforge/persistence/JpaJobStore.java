package com.taskforge.persistence;

import com.taskforge.domain.Job;
import com.taskforge.domain.JobStatus;
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
     * Claims one queued job for a single worker instance. This read and write does not
     * coordinate competing application instances; multi-worker claiming needs a separate design.
     */
    @Transactional
    public Optional<Job> startOldestQueuedForSingleWorker(Instant at) {
        return repository.findFirstByStatusOrderByCreatedAtAscCreatedAtNanoRemainderAscIdAsc(JobStatus.QUEUED)
                .map(mapper::toDomain)
                .map(job -> job.start(at))
                .map(job -> repository.save(mapper.toEntity(job)))
                .map(mapper::toDomain);
    }
}
