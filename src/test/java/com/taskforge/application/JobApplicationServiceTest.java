package com.taskforge.application;

import com.taskforge.domain.Job;
import com.taskforge.domain.JobStatus;
import com.taskforge.domain.JobType;
import com.taskforge.domain.ReportPayload;
import com.taskforge.persistence.JpaJobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JobApplicationServiceTest {

    private final Instant now = Instant.parse("2026-10-16T10:15:30.123456789Z");
    private JpaJobStore store;
    private JobApplicationService service;

    @BeforeEach
    void setUp() {
        store = mock(JpaJobStore.class);
        service = new JobApplicationService(store, Clock.fixed(now, ZoneOffset.UTC),
                new JobExecutionSettings(3, 5000, 300000, 30000, 10000));
    }

    @Test
    void submitCreatesQueuedJobWithServerManagedFields() {
        when(store.createQueued(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Job job = service.submit(JobType.GENERATE_REPORT, "  Quarterly report  ");

        assertThat(job.id()).isNotNull();
        assertThat(job.payload()).isEqualTo(new ReportPayload("Quarterly report"));
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.attemptCount()).isZero();
        assertThat(job.createdAt()).isEqualTo(now);
        assertThat(job.updatedAt()).isEqualTo(now);
        assertThat(job.failureReason()).isEmpty();
        verify(store).createQueued(job);
    }

    @Test
    void invalidReportNameDoesNotReachPersistence() {
        assertThatThrownBy(() -> service.submit(JobType.GENERATE_REPORT, "bad\nname"))
                .isInstanceOf(InvalidJobRequestException.class);
        assertThatThrownBy(() -> service.submit(JobType.GENERATE_REPORT, "x".repeat(101)))
                .isInstanceOf(InvalidJobRequestException.class);
        verifyNoInteractions(store);
    }

    @Test
    void getReturnsStoredJob() {
        Job job = Job.create(UUID.randomUUID(), new ReportPayload("Report"), now);
        when(store.findById(job.id())).thenReturn(Optional.of(job));

        assertThat(service.get(job.id())).isSameAs(job);
    }

    @Test
    void getRejectsUnknownId() {
        UUID id = UUID.randomUUID();
        when(store.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(JobNotFoundException.class)
                .hasMessage("Job not found: " + id);
    }
}
