package com.taskforge.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final ReportPayload PAYLOAD = new ReportPayload("weekly report");

    @Test
    void newJobStartsQueuedWithNoAttemptsOrFailure() {
        Job job = queuedJob();

        assertEquals(ID, job.id());
        assertEquals(JobType.GENERATE_REPORT, job.jobType());
        assertEquals(PAYLOAD, job.payload());
        assertEquals(JobStatus.QUEUED, job.status());
        assertEquals(0, job.attemptCount());
        assertEquals(CREATED_AT, job.createdAt());
        assertEquals(CREATED_AT, job.updatedAt());
        assertTrue(job.failureReason().isEmpty());
    }

    @Test
    void startCreatesRunningJobAndIncrementsAttemptCount() {
        Job queued = queuedJob();
        Instant startedAt = CREATED_AT.plusSeconds(1);

        Job running = queued.start(startedAt);

        assertEquals(JobStatus.RUNNING, running.status());
        assertEquals(1, running.attemptCount());
        assertEquals(startedAt, running.updatedAt());
        assertEquals(JobStatus.QUEUED, queued.status());
        assertEquals(0, queued.attemptCount());
    }

    @Test
    void runningJobCanSucceed() {
        Job running = queuedJob().start(CREATED_AT.plusSeconds(1));
        Instant completedAt = CREATED_AT.plusSeconds(2);

        Job succeeded = running.succeed(completedAt);

        assertEquals(JobStatus.SUCCEEDED, succeeded.status());
        assertEquals(1, succeeded.attemptCount());
        assertEquals(completedAt, succeeded.updatedAt());
        assertTrue(succeeded.failureReason().isEmpty());
    }

    @Test
    void runningJobCanFailWithShortReason() {
        Job running = queuedJob().start(CREATED_AT.plusSeconds(1));

        Job failed = running.fail("  simulated failure  ", CREATED_AT.plusSeconds(2));

        assertEquals(JobStatus.FAILED, failed.status());
        assertEquals(1, failed.attemptCount());
        assertEquals("simulated failure", failed.failureReason().orElseThrow());
    }

    @Test
    void queuedJobCannotFinishWithoutStarting() {
        Job queued = queuedJob();

        assertThrows(IllegalStateException.class, () -> queued.succeed(CREATED_AT.plusSeconds(1)));
        assertThrows(IllegalStateException.class, () -> queued.fail("failure", CREATED_AT.plusSeconds(1)));
        assertEquals(JobStatus.QUEUED, queued.status());
    }

    @Test
    void runningJobCannotStartAgain() {
        Job running = queuedJob().start(CREATED_AT.plusSeconds(1));

        assertThrows(IllegalStateException.class, () -> running.start(CREATED_AT.plusSeconds(2)));
        assertEquals(1, running.attemptCount());
    }

    @Test
    void terminalJobsCannotTransitionAgain() {
        Job running = queuedJob().start(CREATED_AT.plusSeconds(1));
        Job succeeded = running.succeed(CREATED_AT.plusSeconds(2));
        Job failed = running.fail("failure", CREATED_AT.plusSeconds(2));

        assertThrows(IllegalStateException.class, () -> succeeded.start(CREATED_AT.plusSeconds(3)));
        assertThrows(IllegalStateException.class, () -> succeeded.fail("failure", CREATED_AT.plusSeconds(3)));
        assertThrows(IllegalStateException.class, () -> failed.start(CREATED_AT.plusSeconds(3)));
        assertThrows(IllegalStateException.class, () -> failed.succeed(CREATED_AT.plusSeconds(3)));
    }

    @Test
    void transitionCannotMoveTimeBackward() {
        Job queued = queuedJob();
        Job running = queued.start(CREATED_AT.plusSeconds(2));

        assertThrows(IllegalArgumentException.class, () -> running.succeed(CREATED_AT.plusSeconds(1)));
        assertEquals(JobStatus.RUNNING, running.status());
        assertEquals(CREATED_AT.plusSeconds(2), running.updatedAt());
    }

    @Test
    void transitionCanUseSameTimestamp() {
        Job succeeded = queuedJob().start(CREATED_AT).succeed(CREATED_AT);

        assertEquals(JobStatus.SUCCEEDED, succeeded.status());
        assertEquals(CREATED_AT, succeeded.updatedAt());
    }

    @Test
    void retryWaitPreservesAttemptCountAndStartsOneNewAttemptWhenDue() {
        Job running = queuedJob().start(CREATED_AT);
        Instant due = CREATED_AT.plusSeconds(5);
        Job waiting = running.scheduleRetry(due, CREATED_AT.plusSeconds(1));

        assertEquals(JobStatus.RETRY_WAIT, waiting.status());
        assertEquals(1, waiting.attemptCount());
        assertEquals(due, waiting.nextEligibleAt().orElseThrow());
        assertThrows(IllegalStateException.class, () -> waiting.start(due.minusNanos(1)));
        assertEquals(1, waiting.attemptCount());
        assertEquals(2, waiting.start(due).attemptCount());
    }

    @Test
    void thirdStartedAttemptCannotScheduleAFourth() {
        Job first = queuedJob().start(CREATED_AT);
        Job second = first.scheduleRetry(CREATED_AT, CREATED_AT).start(CREATED_AT);
        Job third = second.scheduleRetry(CREATED_AT, CREATED_AT).start(CREATED_AT);

        assertEquals(3, third.attemptCount());
        assertThrows(IllegalStateException.class, () -> third.scheduleRetry(CREATED_AT, CREATED_AT));
        assertEquals(JobStatus.RUNNING, third.status());
    }

    @Test
    void retryDueTimeMayPrecedeNonDecreasingDomainTimestamp() {
        Instant futureUpdate = CREATED_AT.plusSeconds(3600);
        Job waiting = queuedJob().start(futureUpdate).scheduleRetry(CREATED_AT.plusSeconds(5), futureUpdate);

        assertEquals(futureUpdate, waiting.updatedAt());
        assertEquals(CREATED_AT.plusSeconds(5), waiting.nextEligibleAt().orElseThrow());
        assertEquals(futureUpdate, waiting.start(futureUpdate).updatedAt());
    }

    @Test
    void rejectsMissingIdentityPayloadOrTime() {
        assertThrows(NullPointerException.class, () -> Job.create(null, PAYLOAD, CREATED_AT));
        assertThrows(NullPointerException.class, () -> Job.create(ID, null, CREATED_AT));
        assertThrows(NullPointerException.class, () -> Job.create(ID, PAYLOAD, null));
        assertThrows(NullPointerException.class, () -> queuedJob().start(null));
    }

    @Test
    void rejectsBlankMultilineAndLongFailureReasons() {
        Job running = queuedJob().start(CREATED_AT.plusSeconds(1));
        Instant failedAt = CREATED_AT.plusSeconds(2);

        assertThrows(IllegalArgumentException.class, () -> running.fail(" ", failedAt));
        assertThrows(IllegalArgumentException.class, () -> running.fail("line one\nline two", failedAt));
        assertThrows(IllegalArgumentException.class, () -> running.fail("x".repeat(501), failedAt));
        assertThrows(NullPointerException.class, () -> running.fail(null, failedAt));
        assertFalse(running.failureReason().isPresent());
    }

    private static Job queuedJob() {
        return Job.create(ID, PAYLOAD, CREATED_AT);
    }
}
