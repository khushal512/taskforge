package com.taskforge.domain;

import java.util.Objects;

public record ReportPayload(String reportName) implements JobPayload {

    public ReportPayload {
        Objects.requireNonNull(reportName, "reportName");
        reportName = reportName.strip();

        if (reportName.isBlank() || reportName.length() > 100
                || reportName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("reportName must be one line with 1 to 100 characters");
        }
    }

    @Override
    public JobType jobType() {
        return JobType.GENERATE_REPORT;
    }
}
