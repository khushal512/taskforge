ALTER TABLE jobs ADD COLUMN max_attempts integer NOT NULL DEFAULT 3;
ALTER TABLE jobs ADD COLUMN next_eligible_at timestamptz;
ALTER TABLE jobs ADD COLUMN next_eligible_at_nano_remainder smallint NOT NULL DEFAULT 0;
ALTER TABLE jobs ADD COLUMN lease_expires_at timestamptz;
UPDATE jobs SET lease_expires_at = clock_timestamp() - interval '1 second'
WHERE status = 'RUNNING';

ALTER TABLE jobs DROP CONSTRAINT jobs_status_check;
ALTER TABLE jobs DROP CONSTRAINT jobs_attempt_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_status_check
    CHECK (status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED'));
ALTER TABLE jobs ADD CONSTRAINT jobs_attempt_check CHECK (
    max_attempts >= 1 AND attempt_count BETWEEN 0 AND max_attempts AND (
        (status = 'QUEUED' AND attempt_count = 0) OR
        (status = 'RETRY_WAIT' AND attempt_count BETWEEN 1 AND max_attempts - 1) OR
        (status IN ('RUNNING', 'SUCCEEDED', 'FAILED') AND attempt_count >= 1)
    )
);
ALTER TABLE jobs ADD CONSTRAINT jobs_next_eligible_check CHECK (
    (status = 'RETRY_WAIT') = (next_eligible_at IS NOT NULL)
    AND next_eligible_at_nano_remainder BETWEEN 0 AND 999
);
ALTER TABLE jobs ADD CONSTRAINT jobs_lease_check CHECK (
    (status = 'RUNNING') = (lease_expires_at IS NOT NULL)
);

CREATE TABLE job_attempts (
    job_id uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt_number integer NOT NULL CHECK (attempt_number >= 1),
    started_at timestamptz NOT NULL,
    started_at_nano_remainder smallint NOT NULL CHECK (started_at_nano_remainder BETWEEN 0 AND 999),
    finished_at timestamptz,
    finished_at_nano_remainder smallint NOT NULL DEFAULT 0
        CHECK (finished_at_nano_remainder BETWEEN 0 AND 999),
    outcome varchar(20) CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'RETRY_WAIT')),
    failure_reason varchar(500),
    next_eligible_at timestamptz,
    next_eligible_at_nano_remainder smallint NOT NULL DEFAULT 0
        CHECK (next_eligible_at_nano_remainder BETWEEN 0 AND 999),
    start_time_source varchar(20) NOT NULL
        CHECK (start_time_source IN ('RECORDED', 'LEGACY_DERIVED', 'LEGACY_SYNTHETIC')),
    PRIMARY KEY (job_id, attempt_number),
    CONSTRAINT attempt_outcome_check CHECK (CASE
        WHEN outcome IS NULL THEN finished_at IS NULL AND failure_reason IS NULL
            AND next_eligible_at IS NULL
        WHEN outcome = 'SUCCEEDED' THEN finished_at IS NOT NULL AND failure_reason IS NULL
            AND next_eligible_at IS NULL
        WHEN outcome = 'FAILED' THEN finished_at IS NOT NULL AND failure_reason IS NOT NULL
            AND length(btrim(failure_reason)) > 0 AND next_eligible_at IS NULL
        WHEN outcome = 'RETRY_WAIT' THEN finished_at IS NOT NULL AND failure_reason IS NOT NULL
            AND length(btrim(failure_reason)) > 0 AND next_eligible_at IS NOT NULL
        ELSE false END),
    CONSTRAINT attempt_time_order_check CHECK (
        finished_at IS NULL OR finished_at > started_at OR
        (finished_at = started_at AND finished_at_nano_remainder >= started_at_nano_remainder)
    )
);

INSERT INTO job_attempts (
    job_id, attempt_number, started_at, started_at_nano_remainder,
    finished_at, finished_at_nano_remainder, outcome, failure_reason, start_time_source
)
SELECT id, 1,
       CASE WHEN status = 'RUNNING' THEN updated_at ELSE created_at END,
       CASE WHEN status = 'RUNNING' THEN updated_at_nano_remainder ELSE created_at_nano_remainder END,
       CASE WHEN status = 'RUNNING' THEN NULL ELSE updated_at END,
       CASE WHEN status = 'RUNNING' THEN 0 ELSE updated_at_nano_remainder END,
       CASE WHEN status = 'RUNNING' THEN NULL ELSE status END,
       failure_reason,
       CASE WHEN status = 'RUNNING' THEN 'LEGACY_DERIVED' ELSE 'LEGACY_SYNTHETIC' END
FROM jobs WHERE status <> 'QUEUED';

CREATE INDEX jobs_retry_due_idx ON jobs (next_eligible_at, created_at, id)
    WHERE status = 'RETRY_WAIT';
CREATE INDEX jobs_running_lease_idx ON jobs (lease_expires_at, id)
    WHERE status = 'RUNNING';
