package com.taskforge.worker;

import com.taskforge.domain.JobPayload;
import com.taskforge.domain.JobType;

interface JobHandler {
    JobType jobType();

    /** Produces a simulated in-memory result; Phase 5 does not store job output. */
    String execute(JobPayload payload);
}
