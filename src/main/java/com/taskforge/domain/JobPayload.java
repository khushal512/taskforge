package com.taskforge.domain;

public sealed interface JobPayload permits ReportPayload {
    JobType jobType();
}
