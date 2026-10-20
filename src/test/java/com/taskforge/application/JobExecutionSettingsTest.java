package com.taskforge.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobExecutionSettingsTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void usesBoundedExponentialBackoffForStartedAttempts() {
        JobExecutionSettings settings = new JobExecutionSettings(3, 5000, 6000, 30000, 10000);

        assertThat(settings.retryDueAt(NOW, 1)).isEqualTo(NOW.plusSeconds(5));
        assertThat(settings.retryDueAt(NOW, 2)).isEqualTo(NOW.plusSeconds(6));
    }

    @Test
    void rejectsInvalidLimitsAndHeartbeatInterval() {
        assertThatThrownBy(() -> new JobExecutionSettings(0, 5000, 300000, 30000, 10000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobExecutionSettings(3, 5000, 300000, 30000, 30000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobExecutionSettings(3, 5000, 4000, 30000, 10000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
