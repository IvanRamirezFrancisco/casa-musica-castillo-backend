package com.security.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fail closed without exposing configuration values. Does not change deployed variables. */
@Component
public class ActivationProductionGuard {
    private final Environment env;
    public ActivationProductionGuard(Environment env) { this.env = env; }
    @PostConstruct public void validate() {
        if (Arrays.stream(env.getActiveProfiles()).noneMatch(p -> p.equals("production") || p.equals("prod"))) return;
        URI base;
        try { base = URI.create(env.getProperty("app.base-url", "")); }
        catch (RuntimeException ex) { throw new IllegalStateException("Invalid activation production origin"); }
        String host = base.getHost();
        if (!"https".equals(base.getScheme()) || host == null || base.getUserInfo() != null || base.getRawQuery() != null
            || host.equals("localhost") || host.endsWith(".local") || host.matches("[0-9.:]+"))
            throw new IllegalStateException("Activation requires a public HTTPS frontend origin");
        if (env.getProperty("app.email.brevo.api-key", "").isBlank()
            || !env.getProperty("app.email.sender-address", "").contains("@"))
            throw new IllegalStateException("Activation requires configured Brevo and sender in production");
        if (!"none".equalsIgnoreCase(env.getProperty("server.forward-headers-strategy", "none")))
            throw new IllegalStateException("Use explicit trusted activation proxies, not automatic forwarded headers");
        if (env.getProperty("app.activation.dispatch-ms", Long.class, 1000L) < 100)
            throw new IllegalStateException("Invalid activation dispatcher interval");
        if (!env.getProperty("app.activation.jobs-enabled", Boolean.class, true))
            throw new IllegalStateException("Activation delivery processing must be enabled in production");
        String provider = env.getProperty("EMAIL_PROVIDER", env.getProperty("app.email.provider", "brevo"));
        if (!"brevo".equalsIgnoreCase(provider))
            throw new IllegalStateException("Activation requires Brevo in production");
    }
}
