package com.security.service;
import com.security.config.ActivationProductionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class ActivationBoundaryTest {
    @Test void aUniversalProxyTrustRangeIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new ActivationClientAddress(
            new MockEnvironment().withProperty("app.activation.trusted-proxies","0.0.0.0/0")));
    }
    @Test void untrustedPeerCannotSpoofForwardedAddress() {
        var resolver=new ActivationClientAddress(new MockEnvironment());
        var request=new MockHttpServletRequest(); request.setRemoteAddr("192.0.2.1");
        request.addHeader("X-Forwarded-For","198.51.100.2");
        assertEquals("192.0.2.1",resolver.resolve(request));
    }
    @Test void walksOnlyTrustedSuffixAndRejectsAmbiguousHeaders() {
        var resolver=new ActivationClientAddress(new MockEnvironment().withProperty("app.activation.trusted-proxies","10.0.0.0/8"));
        var request=new MockHttpServletRequest();request.setRemoteAddr("10.0.0.2");
        request.addHeader("X-Forwarded-For","192.0.2.99,198.51.100.2,10.0.0.3");
        assertEquals("198.51.100.2",resolver.resolve(request));
        request.addHeader("X-Forwarded-For","192.0.2.4");
        assertEquals("10.0.0.2",resolver.resolve(request));
    }
    @Test void malformedForwardingCannotCauseDnsLookup() {
        var resolver=new ActivationClientAddress(new MockEnvironment().withProperty("app.activation.trusted-proxies","127.0.0.1"));
        var request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For","attacker.invalid");
        assertEquals("127.0.0.1",resolver.resolve(request));
    }
    @Test void ciphertextIsRandomAuthenticatedAndBoundToChallenge() {
        var crypto=new ActivationCrypto("synthetic-test-key-long-enough-for-domain-separation");
        String first=crypto.seal("synthetic-token:012345","context");
        assertNotEquals(first,crypto.seal("synthetic-token:012345","context"));
        assertFalse(first.contains("012345"));
        assertEquals("synthetic-token:012345",crypto.open(first,"context"));
        assertThrows(IllegalStateException.class,()->crypto.open(first,"other"));
        assertNotEquals(crypto.subject("a@example.invalid"),crypto.subject("b@example.invalid"));
    }
    @Test void insecureProductionConfigurationFailsClosed() {
        var env=new MockEnvironment();env.setActiveProfiles("production");
        env.withProperty("app.base-url","http://localhost:4200");
        assertThrows(IllegalStateException.class,()->new ActivationProductionGuard(env).validate());
        env.withProperty("app.base-url","https://shop.example.test")
            .withProperty("app.email.brevo.api-key","synthetic-only").withProperty("app.email.sender-address","sender@example.test");
        assertDoesNotThrow(()->new ActivationProductionGuard(env).validate());
        env.withProperty("app.activation.jobs-enabled","false");
        assertThrows(IllegalStateException.class,()->new ActivationProductionGuard(env).validate());
        env.withProperty("app.activation.jobs-enabled","true").withProperty("EMAIL_PROVIDER","smtp");
        assertThrows(IllegalStateException.class,()->new ActivationProductionGuard(env).validate());
        env.withProperty("EMAIL_PROVIDER","brevo");
        env.withProperty("server.forward-headers-strategy","framework");
        assertThrows(IllegalStateException.class,()->new ActivationProductionGuard(env).validate());
    }
}
