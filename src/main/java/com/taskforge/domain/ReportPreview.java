package com.taskforge.domain;

import java.util.Objects;

/** Bounded text produced by the simulated report handler; it is not a report file. */
public record ReportPreview(String text) {
    public ReportPreview {
        Objects.requireNonNull(text, "text");
        if (text.isBlank() || text.codePointCount(0, text.length()) > 2048) {
            throw new IllegalArgumentException("Report preview must contain 1 to 2048 characters");
        }
    }
}
