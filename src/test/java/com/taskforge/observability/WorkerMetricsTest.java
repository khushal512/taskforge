package com.taskforge.observability;

import com.taskforge.domain.JobStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerMetricsTest {
    @Test
    void registersOnlyBoundedCompletionOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            WorkerMetrics metrics = new WorkerMetrics(registry);
            metrics.completionCommitted(JobStatus.SUCCEEDED);
            metrics.completionCommitted(JobStatus.FAILED);
            metrics.completionCommitted(JobStatus.RETRY_WAIT);

            assertThat(registry.find("taskforge.worker.completions").counters())
                    .extracting(counter -> counter.getId().getTag("outcome"))
                    .containsExactlyInAnyOrder("SUCCEEDED", "FAILED", "RETRY_WAIT");
            assertThatThrownBy(() -> metrics.completionCommitted(JobStatus.RUNNING))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            registry.close();
        }
    }

    @Test
    void recordsHandlerDurationEvenWhenHandlerThrows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            WorkerMetrics metrics = new WorkerMetrics(registry);

            assertThatThrownBy(() -> metrics.timeHandler(() -> {
                throw new IllegalStateException("handler failed");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(registry.get("taskforge.worker.handler.duration").timer().count()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }
}
