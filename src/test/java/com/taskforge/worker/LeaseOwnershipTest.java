package com.taskforge.worker;

import com.taskforge.application.JobExecutionSettings;
import com.taskforge.domain.Job;
import com.taskforge.domain.JobPayload;
import com.taskforge.domain.JobType;
import com.taskforge.domain.ReportPayload;
import com.taskforge.persistence.ClaimedJob;
import com.taskforge.persistence.JpaJobStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeaseOwnershipTest {
    private final JobExecutionSettings settings = new JobExecutionSettings(3, 5000, 300000, 30000, 10000);

    @Test
    void heartbeatRejectionAndDatabaseErrorBothEndOwnership() {
        JpaJobStore store = mock(JpaJobStore.class);
        LeaseHeartbeat heartbeat = new LeaseHeartbeat(store, settings);
        ClaimedJob claim = claim();
        try {
            LeaseHeartbeat.Ownership lost = new LeaseHeartbeat.Ownership();
            when(store.renewLease(claim.id(), claim.attemptNumber())).thenReturn(false);
            heartbeat.renewOnce(claim, lost);
            assertThat(lost.state()).isEqualTo(LeaseHeartbeat.State.LOST);

            LeaseHeartbeat.Ownership unknown = new LeaseHeartbeat.Ownership();
            when(store.renewLease(claim.id(), claim.attemptNumber()))
                    .thenThrow(new IllegalStateException("database detail"));
            heartbeat.renewOnce(claim, unknown);
            assertThat(unknown.state()).isEqualTo(LeaseHeartbeat.State.UNKNOWN);
        } finally {
            heartbeat.shutdown();
        }
    }

    @Test
    void taskIsCancelledEvenIfOwnershipWasLostBeforeFutureWasStored() {
        LeaseHeartbeat.Ownership ownership = new LeaseHeartbeat.Ownership();
        ownership.lose(LeaseHeartbeat.State.LOST);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);

        ownership.setFuture(scheduled);

        verify(scheduled).cancel(false);
    }

    @Test
    void activeIsNotAValidLossStateAndCannotCancelAnActiveHeartbeat() {
        LeaseHeartbeat.Ownership ownership = new LeaseHeartbeat.Ownership();
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        ownership.setFuture(scheduled);

        assertThatThrownBy(() -> ownership.lose(LeaseHeartbeat.State.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ownership.active()).isTrue();
        verify(scheduled, never()).cancel(false);
    }

    @Test
    void closeMakesOwnershipInactiveAndCancelsEvenIfFutureArrivesLater() {
        LeaseHeartbeat.Ownership ownership = new LeaseHeartbeat.Ownership();
        ownership.close();
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        ownership.setFuture(scheduled);

        assertThat(ownership.state()).isEqualTo(LeaseHeartbeat.State.CLOSED);
        assertThat(ownership.active()).isFalse();
        verify(scheduled).cancel(false);
        ownership.lose(LeaseHeartbeat.State.LOST);
        assertThat(ownership.state()).isEqualTo(LeaseHeartbeat.State.CLOSED);
    }

    @Test
    void closingDuringInFlightRenewalCannotRestoreActiveOwnership() throws Exception {
        JpaJobStore store = mock(JpaJobStore.class);
        LeaseHeartbeat heartbeat = new LeaseHeartbeat(store, settings);
        ClaimedJob claim = claim();
        LeaseHeartbeat.Ownership ownership = new LeaseHeartbeat.Ownership();
        CountDownLatch renewalEntered = new CountDownLatch(1);
        CountDownLatch releaseRenewal = new CountDownLatch(1);
        when(store.renewLease(claim.id(), claim.attemptNumber())).thenAnswer(invocation -> {
            renewalEntered.countDown();
            if (!releaseRenewal.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for renewal release");
            }
            return true;
        });
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            Future<?> renewal = threads.submit(() -> heartbeat.renewOnce(claim, ownership));
            assertThat(renewalEntered.await(10, TimeUnit.SECONDS)).isTrue();
            ownership.close();
            releaseRenewal.countDown();
            renewal.get(10, TimeUnit.SECONDS);
            assertThat(ownership.state()).isEqualTo(LeaseHeartbeat.State.CLOSED);
            assertThat(ownership.active()).isFalse();
        } finally {
            releaseRenewal.countDown();
            threads.shutdownNow();
            assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            heartbeat.shutdown();
        }
    }

    @Test
    void workerDoesNotCompleteAfterOwnershipIsClosedDuringHandler() {
        JpaJobStore store = mock(JpaJobStore.class);
        LeaseHeartbeat heartbeat = mock(LeaseHeartbeat.class);
        LeaseHeartbeat.Ownership ownership = new LeaseHeartbeat.Ownership();
        ClaimedJob claim = claim();
        when(store.claimNextEligible()).thenReturn(Optional.of(claim));
        when(heartbeat.start(claim)).thenReturn(ownership);
        JobHandler handler = new JobHandler() {
            @Override public JobType jobType() { return JobType.GENERATE_REPORT; }
            @Override public String execute(JobPayload payload) {
                ownership.close();
                return "Discarded preview";
            }
        };

        JobWorker worker = new JobWorker(store, new JobHandlerRegistry(List.of(handler)), heartbeat);
        assertThat(worker.processOne()).isTrue();
        verify(store, never()).completeSuccess(any());
        verify(store, never()).completeFailure(any(), anyBoolean(), any());
    }

    @Test
    void workerDiscardsResultAfterHeartbeatReportsLostOwnership() {
        JpaJobStore store = mock(JpaJobStore.class);
        LeaseHeartbeat heartbeat = mock(LeaseHeartbeat.class);
        LeaseHeartbeat.Ownership ownership = new LeaseHeartbeat.Ownership();
        ClaimedJob claim = claim();
        when(store.claimNextEligible()).thenReturn(Optional.of(claim));
        when(heartbeat.start(claim)).thenReturn(ownership);
        JobHandler handler = new JobHandler() {
            @Override public JobType jobType() { return JobType.GENERATE_REPORT; }
            @Override public String execute(JobPayload payload) {
                ownership.lose(LeaseHeartbeat.State.LOST);
                return "Preview that must be discarded";
            }
        };

        JobWorker worker = new JobWorker(store, new JobHandlerRegistry(List.of(handler)), heartbeat);
        assertThat(worker.processOne()).isTrue();
        verify(store, never()).completeSuccess(any());
        verify(store, never()).completeFailure(any(), anyBoolean(), any());
    }

    @Test
    void workerDiscardsResultWhenHeartbeatDatabaseErrorMakesOwnershipUnknown() {
        JpaJobStore store = mock(JpaJobStore.class);
        LeaseHeartbeat heartbeat = mock(LeaseHeartbeat.class);
        LeaseHeartbeat.Ownership ownership = new LeaseHeartbeat.Ownership();
        ClaimedJob claim = claim();
        when(store.claimNextEligible()).thenReturn(Optional.of(claim));
        when(heartbeat.start(claim)).thenReturn(ownership);
        JobHandler handler = new JobHandler() {
            @Override public JobType jobType() { return JobType.GENERATE_REPORT; }
            @Override public String execute(JobPayload payload) {
                ownership.lose(LeaseHeartbeat.State.UNKNOWN);
                return "Preview that must be discarded";
            }
        };

        JobWorker worker = new JobWorker(store, new JobHandlerRegistry(List.of(handler)), heartbeat);
        assertThat(worker.processOne()).isTrue();
        verify(store, never()).completeSuccess(any());
        verify(store, never()).completeFailure(any(), anyBoolean(), any());
    }

    private static ClaimedJob claim() {
        Job running = Job.create(UUID.randomUUID(), new ReportPayload("Report"),
                Instant.parse("2020-01-01T00:00:00Z")).start(Instant.parse("2020-01-01T00:00:01Z"));
        return new ClaimedJob(running.id(), 1, running);
    }
}
