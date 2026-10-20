CREATE TABLE job_submission_keys (
    key_digest varchar(64) PRIMARY KEY,
    request_fingerprint varchar(64) NOT NULL,
    job_id uuid NOT NULL UNIQUE REFERENCES jobs(id) ON DELETE RESTRICT,
    CONSTRAINT submission_key_digest_check CHECK (key_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT submission_request_fingerprint_check
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);
