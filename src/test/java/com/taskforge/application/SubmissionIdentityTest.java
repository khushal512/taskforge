package com.taskforge.application;

import com.taskforge.domain.JobType;
import com.taskforge.domain.ReportPayload;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubmissionIdentityTest {
    @Test
    void keyAndLogicalRequestHaveStableVersionedDigests() {
        SubmissionIdentity first = SubmissionIdentity.from(" key-1 ", JobType.GENERATE_REPORT,
                new ReportPayload(" Quarterly report "));
        SubmissionIdentity equivalent = SubmissionIdentity.from("key-1", JobType.GENERATE_REPORT,
                new ReportPayload("Quarterly report"));

        assertThat(first).isEqualTo(equivalent);
        assertThat(first.keyDigest())
                .isEqualTo("78d3f46aabae52693e4cea67c59b2b80c1caf972c198d0918a06a792603a41f9");
        assertThat(first.requestFingerprint())
                .isEqualTo("cba466c58f4a82261abf3a10c752a91bf30fb49e1ac0747cacdd8397f8bf0066");
        assertThat(SubmissionIdentity.from("another-key", JobType.GENERATE_REPORT,
                new ReportPayload("Quarterly report")).requestFingerprint())
                .isEqualTo(first.requestFingerprint());
        assertThat(SubmissionIdentity.from("key-1", JobType.GENERATE_REPORT,
                new ReportPayload("Different report")).requestFingerprint())
                .isNotEqualTo(first.requestFingerprint());
    }

    @Test
    void keyPolicyStripsEdgesAndRejectsInvalidOrUnboundedValues() {
        ReportPayload payload = new ReportPayload("Report");
        assertThat(SubmissionIdentity.from("x".repeat(128), JobType.GENERATE_REPORT, payload)
                .keyDigest()).hasSize(64);
        for (String invalid : new String[] {"", "  ", "internal space", "line\nbreak", "é",
                "x".repeat(129)}) {
            assertThatThrownBy(() -> SubmissionIdentity.from(invalid, JobType.GENERATE_REPORT, payload))
                    .isInstanceOf(InvalidJobRequestException.class)
                    .hasMessageContaining("Idempotency-Key");
        }
    }
}
