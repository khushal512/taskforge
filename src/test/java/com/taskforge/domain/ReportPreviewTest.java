package com.taskforge.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportPreviewTest {
    @Test
    void acceptsNonblankBoundedText() {
        assertThat(new ReportPreview("x".repeat(2048)).text()).hasSize(2048);
    }

    @Test
    void rejectsMissingBlankAndOversizedText() {
        assertThatThrownBy(() -> new ReportPreview(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReportPreview(" \n\t")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportPreview("x".repeat(2049))).isInstanceOf(IllegalArgumentException.class);
    }
}
