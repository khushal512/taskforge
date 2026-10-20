package com.taskforge.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class WorkerSchedulerTest {

    @Test
    void aFailedCycleDoesNotPreventTheNextCycle(CapturedOutput output) {
        JobWorker worker = mock(JobWorker.class);
        when(worker.processOne()).thenThrow(new IllegalStateException("database unavailable")).thenReturn(true);
        WorkerScheduler scheduler = new WorkerScheduler(worker);

        scheduler.poll();
        scheduler.poll();

        verify(worker, times(2)).processOne();
        assertThat(output.getAll()).contains("event=worker_cycle_failed exception_type=IllegalStateException")
                .doesNotContain("database unavailable");
    }
}
