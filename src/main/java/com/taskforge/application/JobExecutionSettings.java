package com.taskforge.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class JobExecutionSettings {
    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration delayCap;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;

    public JobExecutionSettings(
            @Value("${taskforge.worker.max-attempts:3}") int maxAttempts,
            @Value("${taskforge.worker.retry-base-delay-ms:5000}") long baseDelayMs,
            @Value("${taskforge.worker.retry-delay-cap-ms:300000}") long delayCapMs,
            @Value("${taskforge.worker.lease-duration-ms:30000}") long leaseDurationMs,
            @Value("${taskforge.worker.heartbeat-interval-ms:10000}") long heartbeatIntervalMs) {
        if (maxAttempts < 1 || maxAttempts > 100 || baseDelayMs < 1 || delayCapMs < baseDelayMs
                || leaseDurationMs < 1 || heartbeatIntervalMs < 1
                || heartbeatIntervalMs >= leaseDurationMs) {
            throw new IllegalArgumentException("Invalid worker retry or lease settings");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelay = Duration.ofMillis(baseDelayMs);
        this.delayCap = Duration.ofMillis(delayCapMs);
        this.leaseDuration = Duration.ofMillis(leaseDurationMs);
        this.heartbeatInterval = Duration.ofMillis(heartbeatIntervalMs);
    }

    public int maxAttempts() { return maxAttempts; }
    public Duration leaseDuration() { return leaseDuration; }
    public Duration heartbeatInterval() { return heartbeatInterval; }

    public Instant retryDueAt(Instant databaseNow, int startedAttempts) {
        if (startedAttempts < 1) {
            throw new IllegalArgumentException("Attempt number must be positive");
        }
        Duration delay = baseDelay;
        for (int i = 1; i < startedAttempts && delay.compareTo(delayCap) < 0; i++) {
            delay = delay.compareTo(delayCap.dividedBy(2)) >= 0
                    ? delayCap : delay.multipliedBy(2);
        }
        return databaseNow.plus(delay.compareTo(delayCap) > 0 ? delayCap : delay);
    }
}
