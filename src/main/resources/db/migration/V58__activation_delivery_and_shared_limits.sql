-- Additive: existing links retain their expiry and legacy eligibility.
ALTER TABLE auth.verification_tokens ADD COLUMN code_expires_at timestamp;
ALTER TABLE auth.verification_tokens ADD COLUMN delivery_state varchar(24) NOT NULL DEFAULT 'ACCEPTED';
ALTER TABLE auth.verification_tokens ADD COLUMN delivery_payload text;
ALTER TABLE auth.verification_tokens ADD COLUMN delivery_started_at timestamp;
UPDATE auth.verification_tokens SET code_expires_at = LEAST(expiry_date, created_at + interval '15 minutes') WHERE code_digest IS NOT NULL;
ALTER TABLE auth.verification_tokens ADD CONSTRAINT ck_activation_delivery_state CHECK (delivery_state IN ('PENDING','SENDING','ACCEPTED','FAILED','UNKNOWN','CANCELLED'));
CREATE INDEX idx_activation_pending_delivery ON auth.verification_tokens(id) WHERE delivery_state = 'PENDING' AND used = false;
CREATE TABLE auth.activation_request_limits (
 scope varchar(40) NOT NULL, subject_digest varchar(64) NOT NULL,
 window_start bigint NOT NULL, expires_at bigint NOT NULL,
 requests integer NOT NULL CHECK (requests > 0),
 PRIMARY KEY (scope, subject_digest, window_start)
);
CREATE INDEX idx_activation_limit_expiry ON auth.activation_request_limits(expires_at);
