package com.security.service;

import com.security.dto.request.RegisterRequest;
import com.security.exception.AuthFlowException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RegistrationLegalPolicy {
    public record Documents(boolean required, String termsVersion, String privacyVersion) {}

    private final JdbcTemplate jdbc;
    private final Documents documents;

    public RegistrationLegalPolicy(JdbcTemplate jdbc,
            @Value("${app.legal.registration.terms-version:}") String terms,
            @Value("${app.legal.registration.privacy-version:}") String privacy) {
        this.jdbc = jdbc;
        if (terms.isBlank() != privacy.isBlank() || terms.length() > 32 || privacy.length() > 32) {
            throw new IllegalStateException("Configura ambas versiones legales aprobadas.");
        }
        documents = new Documents(!terms.isBlank(), terms, privacy);
    }

    public Documents documents() { return documents; }

    public void validate(RegisterRequest.LegalAcceptance acceptance) {
        if (!documents.required()) return;
        if (acceptance == null || !Boolean.TRUE.equals(acceptance.termsAccepted())
                || !Boolean.TRUE.equals(acceptance.privacyRead())
                || !documents.termsVersion().equals(acceptance.termsVersion())
                || !documents.privacyVersion().equals(acceptance.privacyVersion())) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST,
                    "Consulta los documentos vigentes y confirma por separado los términos y la lectura del aviso.");
        }
    }

    public void record(long userId) {
        if (documents.required()) {
            jdbc.update("""
                    INSERT INTO auth.registration_legal_acceptances (user_id, terms_version, privacy_version)
                    VALUES (?, ?, ?)
                    """, userId, documents.termsVersion(), documents.privacyVersion());
        }
    }
}
