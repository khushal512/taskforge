package com.taskforge.worker;

import com.taskforge.application.JobExecutionSettings;
import com.taskforge.persistence.ClaimedJob;
import com.taskforge.persistence.JpaJobStore;
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
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "taskforge-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    LeaseHeartbeat(JpaJobStore store, JobExecutionSettings settings) {
        this.store = store;
        this.settings = settings;
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
        try {
            if (!store.renewLease(claim.id(), claim.attemptNumber())) {
                ownership.lose(State.LOST);
            }
        } catch (RuntimeException exception) {
            ownership.lose(State.UNKNOWN);
            log.error("Lease renewal failed: {}", exception.getClass().getSimpleName());
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

        void lose(State next) {
            if (next != State.LOST && next != State.UNKNOWN) {
                throw new IllegalArgumentException("Ownership can only be lost or become unknown");
            }
            if (state.compareAndSet(State.ACTIVE, next)) {
                ScheduledFuture<?> scheduled = future.get();
                if (scheduled != null) {
                    scheduled.cancel(false);
                }
            }
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
