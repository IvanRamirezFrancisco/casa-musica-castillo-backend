-- Add two-step Google link confirmation flow state and Google security mail outbox purposes.

DO $$
DECLARE
    con_record record;
BEGIN
    FOR con_record IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'auth'
          AND t.relname = 'google_auth_flows'
          AND c.contype = 'c'
          AND pg_get_constraintdef(c.oid) ILIKE '%flow_type%'
    LOOP
        EXECUTE format('ALTER TABLE auth.google_auth_flows DROP CONSTRAINT %I', con_record.conname);
    END LOOP;
END $$;

ALTER TABLE auth.google_auth_flows
    ADD CONSTRAINT ck_google_auth_flows_flow_type
    CHECK (flow_type IN ('NONCE', 'REGISTRATION', 'REAUTH', 'LINK_CONFIRM'));

CREATE INDEX IF NOT EXISTS idx_google_auth_flows_user_type_active
    ON auth.google_auth_flows(user_id, flow_type)
    WHERE consumed_at IS NULL;

DO $$
DECLARE
    con_record record;
BEGIN
    FOR con_record IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'auth'
          AND t.relname = 'security_mail_outbox'
          AND c.contype = 'c'
          AND pg_get_constraintdef(c.oid) ILIKE '%purpose%'
    LOOP
        EXECUTE format('ALTER TABLE auth.security_mail_outbox DROP CONSTRAINT %I', con_record.conname);
    END LOOP;
END $$;

ALTER TABLE auth.security_mail_outbox
    ADD CONSTRAINT ck_security_mail_outbox_purpose
    CHECK (purpose IN ('PASSWORD_RESET', 'PASSWORD_CHANGED', 'GOOGLE_LINKED', 'GOOGLE_UNLINKED'));

ALTER TABLE auth.security_mail_outbox
    ADD CONSTRAINT ck_security_mail_outbox_reset_token
    CHECK (
        (purpose = 'PASSWORD_RESET' AND reset_token_id IS NOT NULL)
        OR (purpose IN ('PASSWORD_CHANGED', 'GOOGLE_LINKED', 'GOOGLE_UNLINKED') AND reset_token_id IS NULL)
    );
