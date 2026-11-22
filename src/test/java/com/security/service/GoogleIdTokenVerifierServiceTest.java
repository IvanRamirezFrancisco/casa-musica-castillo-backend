package com.security.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.security.config.GoogleAuthProperties;
import com.security.exception.AuthFlowException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleIdTokenVerifierServiceTest {
    private static final String WEB_CLIENT_ID = "1234567890-web.apps.googleusercontent.com";
    private static final String ANDROID_CLIENT_ID = "1234567890-android.apps.googleusercontent.com";
    private static final Instant FIXED_NOW = Instant.parse("2026-11-20T18:00:00Z");

    private static RSAKey trustedKey;
    private static RSAKey attackerKey;

    private GoogleAuthProperties properties;
    private NimbusGoogleIdTokenVerifierService verifier;

    @BeforeAll
    static void generateKeys() throws Exception {
        trustedKey = new RSAKeyGenerator(2048).keyID("google-k1").generate();
        attackerKey = new RSAKeyGenerator(2048).keyID("attacker-k1").generate();
    }

    @BeforeEach
    void setUp() throws Exception {
        properties = new GoogleAuthProperties();
        properties.setEnabled(true);
        properties.setWebClientId(WEB_CLIENT_ID);
        properties.setAllowedClientIds(List.of(WEB_CLIENT_ID, ANDROID_CLIENT_ID));
        properties.setClockSkewSeconds(30);

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(trustedKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(token -> OAuth2TokenValidatorResult.success());
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        verifier = new NimbusGoogleIdTokenVerifierService(properties, decoder, fixedClock);
    }

    @Test
    void acceptsValidGoogleIdTokenWithHttpsIssuerAndHttpsPicture() throws Exception {
        String token = signToken(trustedKey, baseClaims()
                .issuer("https://accounts.google.com")
                .claim("given_name", "  Ana ")
                .claim("family_name", " Castillo  ")
                .claim("name", "Ana Castillo")
                .claim("picture", "https://lh3.googleusercontent.com/a/photo")
                .build());

        var identity = verifier.verify(token, "nonce-value-1234567890");

        assertThat(identity.subject()).isEqualTo("google-sub-001");
        assertThat(identity.email()).isEqualTo("ana.castillo@example.com");
        assertThat(identity.emailVerified()).isTrue();
        assertThat(identity.givenName()).isEqualTo("Ana");
        assertThat(identity.familyName()).isEqualTo("Castillo");
        assertThat(identity.pictureUrl()).isEqualTo("https://lh3.googleusercontent.com/a/photo");
        assertThat(identity.expiresAt()).isEqualTo(FIXED_NOW.plusSeconds(300));
    }

    @Test
    void acceptsBareAccountsGoogleIssuerAndMissingOptionalClaimsStrippingHttpPicture() throws Exception {
        String token = signToken(trustedKey, baseClaims()
                .issuer("accounts.google.com")
                .claim("picture", "http://insecure.example.com/avatar.png")
                .build());

        var identity = verifier.verify(token, "nonce-value-1234567890");

        assertThat(identity.subject()).isEqualTo("google-sub-001");
        assertThat(identity.givenName()).isNull();
        assertThat(identity.familyName()).isNull();
        assertThat(identity.fullName()).isNull();
        assertThat(identity.pictureUrl()).isNull();
    }

    @Test
    void acceptsMultiAudienceTokenWhenAzpIsAllowed() throws Exception {
        String token = signToken(trustedKey, baseClaims()
                .audience(List.of(WEB_CLIENT_ID, "extra-aud"))
                .claim("azp", ANDROID_CLIENT_ID)
                .build());

        var identity = verifier.verify(token, "nonce-value-1234567890");
        assertThat(identity.subject()).isEqualTo("google-sub-001");
    }

    @Test
    void rejectsForgedSignature() throws Exception {
        String forged = signToken(attackerKey, baseClaims().build());

        assertUnauthorized(() -> verifier.verify(forged, "nonce-value-1234567890"));
    }

    @Test
    void rejectsWrongIssuerAudienceAndUnauthorizedAzp() throws Exception {
        String wrongIssuer = signToken(trustedKey, baseClaims().issuer("https://evil.example.com").build());
        assertUnauthorized(() -> verifier.verify(wrongIssuer, "nonce-value-1234567890"));

        String wrongAud = signToken(trustedKey, baseClaims().audience("999999-other.apps.googleusercontent.com").build());
        assertUnauthorized(() -> verifier.verify(wrongAud, "nonce-value-1234567890"));

        String badAzp = signToken(trustedKey, baseClaims()
                .audience(List.of(WEB_CLIENT_ID, "third-party-aud"))
                .claim("azp", "untrusted-azp")
                .build());
        assertUnauthorized(() -> verifier.verify(badAzp, "nonce-value-1234567890"));
    }

    @Test
    void rejectsExpiredTokenFutureNbfAndFutureIat() throws Exception {
        String expired = signToken(trustedKey, baseClaims()
                .expirationTime(Date.from(FIXED_NOW.minusSeconds(31)))
                .build());
        assertUnauthorized(() -> verifier.verify(expired, "nonce-value-1234567890"));

        String futureNbf = signToken(trustedKey, baseClaims()
                .notBeforeTime(Date.from(FIXED_NOW.plusSeconds(31)))
                .build());
        assertUnauthorized(() -> verifier.verify(futureNbf, "nonce-value-1234567890"));

        String futureIat = signToken(trustedKey, baseClaims()
                .issueTime(Date.from(FIXED_NOW.plusSeconds(31)))
                .build());
        assertUnauthorized(() -> verifier.verify(futureIat, "nonce-value-1234567890"));
    }

    @Test
    void rejectsMissingSubjectUnverifiedEmailAndMismatchedNonce() throws Exception {
        String missingSub = signToken(trustedKey, baseClaims().subject(null).build());
        assertUnauthorized(() -> verifier.verify(missingSub, "nonce-value-1234567890"));

        String unverifiedEmail = signToken(trustedKey, baseClaims().claim("email_verified", false).build());
        assertUnauthorized(() -> verifier.verify(unverifiedEmail, "nonce-value-1234567890"));

        String valid = signToken(trustedKey, baseClaims().build());
        assertUnauthorized(() -> verifier.verify(valid, "different-nonce-9999"));
    }

    @Test
    void failsClosedWhenGoogleAuthIsDisabledOrUnconfigured() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> verifier.verify("a.b.c", "nonce"))
                .isInstanceOf(AuthFlowException.class)
                .extracting(ex -> ((AuthFlowException) ex).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void rejectsMalformedTokenEmptyNonceMissingClaimsAndInitializesLazyDecoder() throws Exception {
        assertUnauthorized(() -> verifier.verify(null, "nonce-value-1234567890"));
        assertUnauthorized(() -> verifier.verify("not-a-jwt", "nonce-value-1234567890"));
        assertUnauthorized(() -> verifier.verify("a.b.c", "   "));

        String missingAud = signToken(trustedKey, new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .subject("sub-1")
                .expirationTime(Date.from(FIXED_NOW.plusSeconds(120)))
                .claim("email", "a@example.com")
                .claim("email_verified", true)
                .claim("nonce", "nonce-value-1234567890")
                .build());
        assertUnauthorized(() -> verifier.verify(missingAud, "nonce-value-1234567890"));

        String missingExp = signToken(trustedKey, new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .subject("sub-1")
                .audience(WEB_CLIENT_ID)
                .claim("email", "a@example.com")
                .claim("email_verified", true)
                .claim("nonce", "nonce-value-1234567890")
                .build());
        assertUnauthorized(() -> verifier.verify(missingExp, "nonce-value-1234567890"));

        String longName = "A".repeat(120);
        String longClaimsToken = signToken(trustedKey, baseClaims()
                .claim("given_name", longName)
                .claim("family_name", longName)
                .claim("name", longName)
                .claim("picture", "   ")
                .build());
        var truncated = verifier.verify(longClaimsToken, "nonce-value-1234567890");
        assertThat(truncated.givenName()).hasSize(50);
        assertThat(truncated.familyName()).hasSize(50);
        assertThat(truncated.fullName()).hasSize(100);
        assertThat(truncated.pictureUrl()).isNull();

        properties.setJwkSetUri("https://127.0.0.1:1/certs");
        NimbusGoogleIdTokenVerifierService lazyVerifier = new NimbusGoogleIdTokenVerifierService(properties, null, null);
        String anyValidShape = signToken(trustedKey, baseClaims().build());
        assertUnauthorized(() -> lazyVerifier.verify(anyValidShape, "nonce-value-1234567890"));
    }

    private static JWTClaimsSet.Builder baseClaims() {
        return new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .subject("google-sub-001")
                .audience(WEB_CLIENT_ID)
                .issueTime(Date.from(FIXED_NOW.minusSeconds(10)))
                .notBeforeTime(Date.from(FIXED_NOW.minusSeconds(10)))
                .expirationTime(Date.from(FIXED_NOW.plusSeconds(300)))
                .claim("email", "Ana.Castillo@Example.COM")
                .claim("email_verified", true)
                .claim("nonce", "nonce-value-1234567890");
    }

    private static String signToken(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static void assertUnauthorized(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(AuthFlowException.class)
                .extracting(ex -> ((AuthFlowException) ex).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
