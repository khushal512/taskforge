CREATE INDEX jobs_queued_order_idx
    ON jobs (created_at, created_at_nano_remainder, id)
    WHERE status = 'QUEUED';
