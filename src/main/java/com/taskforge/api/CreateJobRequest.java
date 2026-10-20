package com.taskforge.api;

import com.taskforge.domain.JobType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CreateJobRequest(
        @NotNull JobType jobType,
        @NotNull @Valid ReportPayloadRequest payload) {
}
