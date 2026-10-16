package com.taskforge.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataJobRepository extends JpaRepository<JobEntity, UUID> {
}
