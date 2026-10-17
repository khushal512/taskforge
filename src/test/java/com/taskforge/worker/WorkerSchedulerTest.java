package com.taskforge.worker;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerSchedulerTest {

    @Test
    void aFailedCycleDoesNotPreventTheNextCycle() {
        JobWorker worker = mock(JobWorker.class);
        when(worker.processOne()).thenThrow(new IllegalStateException("database unavailable")).thenReturn(true);
        WorkerScheduler scheduler = new WorkerScheduler(worker);

        scheduler.poll();
        scheduler.poll();

        verify(worker, times(2)).processOne();
    }
}
