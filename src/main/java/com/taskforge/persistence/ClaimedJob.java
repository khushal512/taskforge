package com.taskforge.persistence;

import com.taskforge.domain.Job;

import java.util.UUID;

/** The attempt number is the fencing value for this claim. */
public record ClaimedJob(UUID id, int attemptNumber, Job job) {
}
