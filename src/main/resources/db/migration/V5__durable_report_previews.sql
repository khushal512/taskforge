CREATE TABLE job_results (
    job_id uuid PRIMARY KEY,
    attempt_number integer NOT NULL,
    preview text NOT NULL,
    CONSTRAINT job_results_attempt_fk FOREIGN KEY (job_id, attempt_number)
        REFERENCES job_attempts (job_id, attempt_number) ON DELETE CASCADE,
    CONSTRAINT job_results_preview_check CHECK (
        char_length(preview) BETWEEN 1 AND 2048
        AND preview ~ '[^[:space:]]'
    )
);
