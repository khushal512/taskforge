package com.taskforge.api;

import jakarta.validation.constraints.NotBlank;

public record ReportPayloadRequest(
        @NotBlank String reportName) {
}
