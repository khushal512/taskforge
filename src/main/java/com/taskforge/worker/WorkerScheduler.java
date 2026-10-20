package com.taskforge.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "taskforge.worker.scheduling-enabled", havingValue = "true", matchIfMissing = true)
class WorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkerScheduler.class);
    private final JobWorker worker;

    WorkerScheduler(JobWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${taskforge.worker.poll-delay-ms:1000}")
    void poll() {
        try {
            worker.processOne();
        } catch (RuntimeException exception) {
            // No payload or exception message is logged; expired RUNNING jobs are checked on later cycles.
            log.error("event=worker_cycle_failed exception_type={}", exception.getClass().getSimpleName());
        }
    }
}
