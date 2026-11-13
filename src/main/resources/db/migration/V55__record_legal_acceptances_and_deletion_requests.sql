CREATE TABLE auth.registration_legal_acceptances (
    user_id BIGINT PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    terms_version VARCHAR(32) NOT NULL,
    privacy_version VARCHAR(32) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE auth.account_deletion_requests (
    request_id UUID PRIMARY KEY,
    user_id BIGINT UNIQUE REFERENCES auth.users(id) ON DELETE SET NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
