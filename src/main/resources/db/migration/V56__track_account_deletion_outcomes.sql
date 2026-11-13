ALTER TABLE auth.account_deletion_requests
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD CONSTRAINT account_deletion_valid_outcome CHECK (
        (status IN ('RECEIVED', 'REVIEW_REQUIRED') AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND user_id IS NULL)
    );
