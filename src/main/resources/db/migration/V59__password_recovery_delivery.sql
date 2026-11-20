-- Preserve existing URLs while replacing recoverable bearer tokens with purpose-specific digests.
UPDATE security.password_reset_tokens
 SET token = 'pr1:' || encode(sha256(convert_to(token, 'UTF8')), 'hex')
 WHERE token NOT LIKE 'pr1:%';
ALTER TABLE security.password_reset_tokens ADD COLUMN delivery_state varchar(20) NOT NULL DEFAULT 'ACCEPTED';
ALTER TABLE security.password_reset_tokens ADD CONSTRAINT ck_reset_delivery
 CHECK (delivery_state IN ('PENDING','SENDING','ACCEPTED','FAILED','UNKNOWN','CANCELLED'));
CREATE INDEX idx_reset_user_issued ON security.password_reset_tokens(user_id,created_at DESC,id DESC);
CREATE TABLE auth.security_mail_outbox (
 id bigserial PRIMARY KEY,
 operation_id varchar(36) NOT NULL UNIQUE,
 purpose varchar(24) NOT NULL CHECK (purpose IN ('PASSWORD_RESET','PASSWORD_CHANGED')),
 user_id bigint NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
 reset_token_id bigint REFERENCES security.password_reset_tokens(id) ON DELETE CASCADE,
 state varchar(20) NOT NULL CHECK (state IN ('PENDING','SENDING','ACCEPTED','FAILED','UNKNOWN','CANCELLED')),
 payload text,
 created_at timestamp NOT NULL,
 expires_at timestamp NOT NULL,
 started_at timestamp,
 next_attempt_at timestamp,
 completed_at timestamp,
 CHECK ((purpose = 'PASSWORD_RESET' AND reset_token_id IS NOT NULL) OR
        (purpose = 'PASSWORD_CHANGED' AND reset_token_id IS NULL))
);
CREATE INDEX idx_security_mail_pending ON auth.security_mail_outbox(id) WHERE state='PENDING';
CREATE INDEX idx_security_mail_sending ON auth.security_mail_outbox(started_at) WHERE state='SENDING';
CREATE INDEX idx_security_mail_cleanup ON auth.security_mail_outbox(expires_at);
