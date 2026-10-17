package com.taskforge.worker;

import com.taskforge.domain.JobType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobHandlerRegistryTest {

    @Test
    void selectsTheRegisteredHandler() {
        ReportJobHandler handler = new ReportJobHandler();
        assertThat(new JobHandlerRegistry(List.of(handler)).require(JobType.GENERATE_REPORT)).isSameAs(handler);
    }

    @Test
    void failsPredictablyWhenHandlerIsMissingOrDuplicated() {
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of()).require(JobType.GENERATE_REPORT))
                .isInstanceOf(MissingJobHandlerException.class);
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(new ReportJobHandler(), new ReportJobHandler())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate handler");
    }
}
