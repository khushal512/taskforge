package com.taskforge.worker;

import com.taskforge.domain.ReportPayload;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportJobHandlerTest {

    private final ReportJobHandler handler = new ReportJobHandler();

    @Test
    void createsOnlyAnInMemoryPreviewFromAReportPayload() {
        assertThat(handler.execute(new ReportPayload("Quarterly report")))
                .isEqualTo("# Quarterly report\n\nSimulated report preview.");
    }

    @Test
    void rejectsWrongPayload() {
        assertThatThrownBy(() -> handler.execute(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
