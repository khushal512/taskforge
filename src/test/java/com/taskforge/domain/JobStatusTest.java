package com.taskforge.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobStatusTest {

    @Test
    void transitionTableIsExhaustive() {
        for (JobStatus current : JobStatus.values()) {
            for (JobStatus next : JobStatus.values()) {
                boolean expected = ((current == JobStatus.QUEUED || current == JobStatus.RETRY_WAIT)
                        && next == JobStatus.RUNNING)
                        || (current == JobStatus.RUNNING
                        && (next == JobStatus.SUCCEEDED || next == JobStatus.FAILED
                        || next == JobStatus.RETRY_WAIT));

                assertEquals(expected, current.canTransitionTo(next), current + " -> " + next);
            }
        }
    }

    @Test
    void nullIsNotATransitionTarget() {
        assertThrows(NullPointerException.class, () -> JobStatus.QUEUED.canTransitionTo(null));
    }
}
