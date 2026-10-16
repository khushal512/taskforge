package com.taskforge.persistence;

import com.taskforge.domain.Job;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
}
