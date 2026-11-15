package com.security.config;

import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.util.ContentCachingRequestWrapper;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeConfigurationTest {
    @Test
    void bcryptAdapterRetainsPasswordVerificationAndUpgradeDecisions() {
        var encoder = new SecurityConfig().passwordEncoder();
        String password = "A synthetic password";
        String oldHash = new BCryptPasswordEncoder(4).encode(password);
        String currentHash = encoder.encode(password);

        assertThat(encoder.matches(password, oldHash)).isTrue();
        assertThat(encoder.matches(password, currentHash)).isTrue();
        assertThat(encoder.matches("ñ".repeat(37), currentHash)).isFalse();
        assertThat(encoder.upgradeEncoding(oldHash)).isTrue();
        assertThat(encoder.upgradeEncoding(currentHash)).isFalse();
    }

    @Test
    void paymentWebhookRetainsTheUnmodifiedBodyForVerification() throws Exception {
        Filter filter = new WebhookRequestCacheConfig().webhookRequestCacheFilter().getFilter();
        var request = new MockHttpServletRequest("POST", "/api/webhooks/mercado-pago");
        byte[] payload = "{\"data\": {\"id\": \"synthetic\"}}".getBytes(StandardCharsets.UTF_8);
        request.setContent(payload);

        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            assertThat(wrapped).isInstanceOf(ContentCachingRequestWrapper.class);
            assertThat(wrapped.getInputStream().readAllBytes()).containsExactly(payload);
            assertThat(((ContentCachingRequestWrapper) wrapped).getContentAsByteArray()).containsExactly(payload);
        });
    }
}
