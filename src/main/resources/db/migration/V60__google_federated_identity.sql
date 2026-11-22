-- Google Sign-In, federated registration, external identity linking, and passwordless local accounts.
ALTER TABLE auth.users ALTER COLUMN password DROP NOT NULL;

ALTER TABLE auth.users
    ADD CONSTRAINT ck_users_password_nonblank
    CHECK (password IS NULL OR length(trim(password)) >= 8);

ALTER TABLE auth.users
    ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(500);

CREATE TABLE auth.external_identities (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('GOOGLE')),
    provider_subject VARCHAR(255) NOT NULL,
    provider_email VARCHAR(255),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    picture_url VARCHAR(500),
    linked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP,
    CONSTRAINT uk_external_identities_provider_subject UNIQUE (provider, provider_subject),
    CONSTRAINT uk_external_identities_user_provider UNIQUE (user_id, provider)
);

CREATE INDEX idx_external_identities_user ON auth.external_identities(user_id);

CREATE TABLE auth.google_auth_flows (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    flow_type VARCHAR(24) NOT NULL CHECK (flow_type IN ('NONCE', 'REGISTRATION', 'REAUTH')),
    user_id BIGINT REFERENCES auth.users(id) ON DELETE CASCADE,
    session_jti VARCHAR(255),
    provider VARCHAR(32) NOT NULL DEFAULT 'GOOGLE',
    provider_subject VARCHAR(255),
    email VARCHAR(255),
    given_name VARCHAR(100),
    family_name VARCHAR(100),
    picture_url VARCHAR(500),
    purpose VARCHAR(40),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP
);

CREATE INDEX idx_google_auth_flows_expires ON auth.google_auth_flows(expires_at);
CREATE INDEX idx_google_auth_flows_user ON auth.google_auth_flows(user_id) WHERE user_id IS NOT NULL;

CREATE TABLE auth.consumed_google_tokens (
    token_digest VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_consumed_google_tokens_expires ON auth.consumed_google_tokens(expires_at);
