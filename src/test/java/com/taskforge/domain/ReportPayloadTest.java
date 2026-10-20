package com.taskforge.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportPayloadTest {

    @Test
    void trimsAndIdentifiesTheKnownJobType() {
        ReportPayload payload = new ReportPayload("  weekly summary  ");

        assertEquals("weekly summary", payload.reportName());
        assertEquals(JobType.GENERATE_REPORT, payload.jobType());
    }

    @Test
    void rejectsBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> new ReportPayload("   "));
    }

    @Test
    void rejectsNamesOverOneHundredCharacters() {
        assertThrows(IllegalArgumentException.class, () -> new ReportPayload("x".repeat(101)));
    }

    @Test
    void rejectsMultilineNames() {
        assertThrows(IllegalArgumentException.class, () -> new ReportPayload("first\nsecond"));
    }

    @Test
    void rejectsNullNames() {
        assertThrows(NullPointerException.class, () -> new ReportPayload(null));
    }
}
