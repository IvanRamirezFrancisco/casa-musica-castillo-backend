package com.security.service;

import java.time.Instant;

public interface GoogleIdTokenVerifierService {
    record VerifiedGoogleIdentity(
            String subject,
            String email,
            boolean emailVerified,
            String givenName,
            String familyName,
            String fullName,
            String pictureUrl,
            Instant expiresAt
    ) {
        @Override
        public String toString() {
            return "VerifiedGoogleIdentity[subject=" + subject + ", emailVerified=" + emailVerified + "]";
        }
    }

    VerifiedGoogleIdentity verify(String idToken, String expectedNonce);
}
