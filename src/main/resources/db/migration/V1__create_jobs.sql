CREATE TABLE jobs (
    id uuid PRIMARY KEY,
    job_type varchar(40) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(20) NOT NULL,
    attempt_count integer NOT NULL,
    created_at timestamptz NOT NULL,
    created_at_nano_remainder smallint NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_at_nano_remainder smallint NOT NULL,
    failure_reason varchar(500),
    CONSTRAINT jobs_type_check CHECK (job_type = 'GENERATE_REPORT'),
    CONSTRAINT jobs_payload_object_check CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT jobs_status_check CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT jobs_attempt_check CHECK (
        (status = 'QUEUED' AND attempt_count = 0)
        OR (status <> 'QUEUED' AND attempt_count = 1)
    ),
    CONSTRAINT jobs_created_nano_check CHECK (created_at_nano_remainder BETWEEN 0 AND 999),
    CONSTRAINT jobs_updated_nano_check CHECK (updated_at_nano_remainder BETWEEN 0 AND 999),
    CONSTRAINT jobs_time_order_check CHECK (
        updated_at > created_at OR
        (updated_at = created_at AND updated_at_nano_remainder >= created_at_nano_remainder)
    ),
    CONSTRAINT jobs_failure_reason_check CHECK (
        (status = 'FAILED' AND failure_reason IS NOT NULL AND length(btrim(failure_reason)) > 0)
        OR (status <> 'FAILED' AND failure_reason IS NULL)
    )
);
