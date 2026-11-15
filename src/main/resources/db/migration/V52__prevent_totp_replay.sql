-- A monotonic accepted TOTP counter, never a code or code hash.
-- Existing users start with NULL. Quota window resets must retain this value.
ALTER TABLE auth.login_factor_limits
    ADD COLUMN last_verified_totp_slot BIGINT,
    ADD CONSTRAINT login_factor_totp_slot_nonnegative
        CHECK (last_verified_totp_slot IS NULL OR last_verified_totp_slot >= 0);
