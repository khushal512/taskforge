package com.taskforge.application;

import com.taskforge.domain.JobType;
import com.taskforge.domain.ReportPayload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** Versioned digests for an optional submission key and the normalized logical request. */
record SubmissionIdentity(String keyDigest, String requestFingerprint) {
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{1,128}");

    static SubmissionIdentity from(String rawKey, JobType jobType, ReportPayload payload) {
        // Surrounding whitespace is ignored; internal whitespace and non-ASCII characters are rejected.
        String key = Objects.requireNonNull(rawKey, "rawKey").strip();
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new InvalidJobRequestException(
                    "Idempotency-Key must contain 1 to 128 letters, digits, '.', '_', '~', or '-'");
        }
        Objects.requireNonNull(jobType, "jobType");
        Objects.requireNonNull(payload, "payload");
        // ReportPayload rejects controls, so NUL separates the fixed v1 fields unambiguously.
        String canonicalRequest = "taskforge-request:v1\0" + jobType.name() + "\0" + payload.reportName();
        return new SubmissionIdentity(sha256("taskforge-key:v1\0" + key), sha256(canonicalRequest));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
