ALTER TABLE auth.users ADD COLUMN email_verified_at timestamp(6);
ALTER TABLE auth.verification_tokens ADD COLUMN recipient_email varchar(100);
