package com.taskforge.worker;

import com.taskforge.application.JobExecutionSettings;
import com.taskforge.persistence.ClaimedJob;
import com.taskforge.persistence.JpaJobStore;
import com.taskforge.observability.WorkerMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Component
class LeaseHeartbeat {
    private static final Logger log = LoggerFactory.getLogger(LeaseHeartbeat.class);

    private final JpaJobStore store;
    private final JobExecutionSettings settings;
    private final WorkerMetrics metrics;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "taskforge-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    LeaseHeartbeat(JpaJobStore store, JobExecutionSettings settings, WorkerMetrics metrics) {
        this.store = store;
        this.settings = settings;
        this.metrics = metrics;
    }

    Ownership start(ClaimedJob claim) {
        Ownership ownership = new Ownership();
        long interval = settings.heartbeatInterval().toMillis();
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> renewOnce(claim, ownership), interval, interval, TimeUnit.MILLISECONDS);
        ownership.setFuture(future);
        return ownership;
    }

    void renewOnce(ClaimedJob claim, Ownership ownership) {
        if (!ownership.active()) {
            return;
        }
        boolean renewed;
        try {
            renewed = store.renewLease(claim.id(), claim.attemptNumber());
        } catch (RuntimeException exception) {
            ownership.lose(State.UNKNOWN);
            log.error("event=lease_renewal_error attempt={} exception_type={}",
                    claim.attemptNumber(), exception.getClass().getSimpleName());
            return;
        }
        if (!renewed && ownership.lose(State.LOST)) {
            log.warn("event=ownership_lost attempt={}", claim.attemptNumber());
            try {
                metrics.ownershipLost();
            } catch (RuntimeException exception) {
                log.warn("event=worker_observation_error exception_type={}", exception.getClass().getSimpleName());
            }
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    enum State { ACTIVE, LOST, UNKNOWN, CLOSED }

    static final class Ownership implements AutoCloseable {
        private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
        private final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();

        boolean active() { return state.get() == State.ACTIVE; }
        State state() { return state.get(); }

        void setFuture(ScheduledFuture<?> scheduled) {
            future.set(scheduled);
            if (!active()) {
                scheduled.cancel(false);
            }
        }

        boolean lose(State next) {
            if (next != State.LOST && next != State.UNKNOWN) {
                throw new IllegalArgumentException("Ownership can only be lost or become unknown");
            }
            if (state.compareAndSet(State.ACTIVE, next)) {
                ScheduledFuture<?> scheduled = future.get();
                if (scheduled != null) {
                    scheduled.cancel(false);
                }
                return true;
            }
            return false;
        }

        @Override
        public void close() {
            state.compareAndSet(State.ACTIVE, State.CLOSED);
            ScheduledFuture<?> scheduled = future.get();
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }
}
