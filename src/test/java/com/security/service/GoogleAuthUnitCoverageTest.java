package com.security.service;

import com.security.config.GoogleAuthProperties;
import com.security.entity.ExternalIdentity;
import com.security.entity.RefreshToken;
import com.security.entity.User;
import com.security.exception.AuthFlowException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleAuthUnitCoverageTest {

    @Test
    void googleAuthPropertiesNormalizeClientsAndConfiguredState() {
        GoogleAuthProperties props = new GoogleAuthProperties();
        assertThat(props.isConfigured()).isFalse();

        props.setWebClientId(null);
        assertThat(props.getWebClientId()).isEmpty();

        props.setAllowedClientIds(null);
        assertThat(props.getAllowedClientIds()).isEmpty();

        props.setWebClientId("  web-id-1  ");
        props.setAllowedClientIds(List.of(" android-id-1 , android-id-2 ", " ", "web-id-1"));
        props.setJwkSetUri("https://www.googleapis.com/oauth2/v3/certs");
        props.setClockSkewSeconds(45);
        props.setChallengeTtlSeconds(240);
        props.setRegistrationTtlSeconds(480);
        props.setReauthTtlSeconds(180);

        assertThat(props.isConfigured()).isTrue();
        assertThat(props.getEffectiveAllowedClientIds()).containsExactlyInAnyOrder("web-id-1", "android-id-1", "android-id-2");
        assertThat(props.getClockSkewSeconds()).isEqualTo(45);
        assertThat(props.getChallengeTtlSeconds()).isEqualTo(240);
        assertThat(props.getRegistrationTtlSeconds()).isEqualTo(480);
        assertThat(props.getReauthTtlSeconds()).isEqualTo(180);
    }

    @Test
    void refreshTokenAndExternalIdentityEntitiesExposeLifecycleFields() {
        User user = new User();
        user.setEmail("entity@example.com");

        LocalDateTime future = LocalDateTime.now().plusHours(2);
        RefreshToken token = new RefreshToken("hash-1", user, future);
        LocalDateTime created = LocalDateTime.now();
        token.setId(15L);
        token.setCreatedAt(created);
        token.setLastUsedAt(created);
        token.setRotatedAt(created);
        token.setRevokedAt(created);
        token.setRevokeReason("ROTATED");
        token.setUserAgent("Android-App");
        token.setIpAddress("198.51.100.1");
        token.setFamilyId("fam-1");
        token.setSessionJti("jti-1");
        token.setRevoked(true);

        assertThat(token.getId()).isEqualTo(15L);
        assertThat(token.getToken()).isEqualTo("hash-1");
        assertThat(token.getUser()).isSameAs(user);
        assertThat(token.getCreatedAt()).isEqualTo(created);
        assertThat(token.getLastUsedAt()).isEqualTo(created);
        assertThat(token.getRotatedAt()).isEqualTo(created);
        assertThat(token.getRevokedAt()).isEqualTo(created);
        assertThat(token.getRevokeReason()).isEqualTo("ROTATED");
        assertThat(token.getUserAgent()).isEqualTo("Android-App");
        assertThat(token.getIpAddress()).isEqualTo("198.51.100.1");
        assertThat(token.isExpired()).isFalse();

        token.setAbsoluteExpiresAt(null);
        assertThat(token.isExpired()).isFalse();

        token.setExpiryDate(LocalDateTime.now().minusSeconds(5));
        assertThat(token.isExpired()).isTrue();

        token.setExpiryDate(future);
        token.setAbsoluteExpiresAt(LocalDateTime.now().minusSeconds(5));
        assertThat(token.isExpired()).isTrue();

        ExternalIdentity ext = new ExternalIdentity();
        ext.setId(42L);
        ext.setUser(user);
        ext.setProvider("GOOGLE");
        ext.setProviderSubject("sub-42");
        ext.setProviderEmail("ext@example.com");
        ext.setEmailVerified(true);
        ext.setPictureUrl("https://lh3.googleusercontent.com/p");
        ext.setLinkedAt(created);
        ext.setLastLoginAt(created);

        assertThat(ext.getId()).isEqualTo(42L);
        assertThat(ext.getUser()).isSameAs(user);
        assertThat(ext.getProvider()).isEqualTo("GOOGLE");
        assertThat(ext.getProviderSubject()).isEqualTo("sub-42");
        assertThat(ext.getProviderEmail()).isEqualTo("ext@example.com");
        assertThat(ext.isEmailVerified()).isTrue();
        assertThat(ext.getPictureUrl()).isEqualTo("https://lh3.googleusercontent.com/p");
        assertThat(ext.getLinkedAt()).isEqualTo(created);
        assertThat(ext.getLastLoginAt()).isEqualTo(created);
    }

    @Test
    void activationClientAddressHandlesNullUntrustedAndTrustedForwarding() {
        MockEnvironment untrustedEnv = new MockEnvironment();
        ActivationClientAddress untrusted = new ActivationClientAddress(untrustedEnv);
        assertThat(untrusted.resolve(null)).isNull();

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("198.51.100.9");
        direct.addHeader("X-Forwarded-For", "203.0.113.5");
        assertThat(untrusted.resolve(direct)).isEqualTo("198.51.100.9");

        MockEnvironment trustedEnv = new MockEnvironment().withProperty("app.activation.trusted-proxies", "10.0.0.0/8");
        ActivationClientAddress trusted = new ActivationClientAddress(trustedEnv);
        MockHttpServletRequest proxied = new MockHttpServletRequest();
        proxied.setRemoteAddr("10.1.2.3");
        proxied.addHeader("X-Forwarded-For", "203.0.113.7, 10.1.2.4");
        assertThat(trusted.resolve(proxied)).isEqualTo("203.0.113.7");

        MockEnvironment invalidEnv = new MockEnvironment().withProperty("app.activation.trusted-proxies", "0.0.0.0/0");
        assertThatThrownBy(() -> new ActivationClientAddress(invalidEnv))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authFlowExceptionCarriesHttpStatus() {
        AuthFlowException ex = new AuthFlowException(org.springframework.http.HttpStatus.CONFLICT, "conflict");
        assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(ex.getMessage()).isEqualTo("conflict");
    }
}
