package com.security.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Retains existing account/IP windows; adds configurable delivery budgets. */
@Component
public class ActivationPolicy {
    public final int cooldown, window, emissions, daily, globalMinute, globalDaily, otpSeconds, linkSeconds;
    public final int registerIp, resendIp, verifyIp;
    public ActivationPolicy(Environment env) {
        cooldown = value(env, "cooldown-seconds", 30);
        window = value(env, "window-seconds", 300);
        emissions = value(env, "emissions-per-window", 3);
        daily = value(env, "recipient-daily", 10);
        globalMinute = value(env, "global-per-minute", 100);
        globalDaily = value(env, "global-daily", 1000);
        otpSeconds = value(env, "otp-seconds", 900);
        linkSeconds = value(env, "link-seconds", 86400);
        registerIp = value(env, "register-ip", 30);
        resendIp = value(env, "resend-ip", 30);
        verifyIp = value(env, "verify-ip", 60);
        if (otpSeconds > 900 || linkSeconds < otpSeconds || cooldown > window)
            throw new IllegalArgumentException("Invalid activation expiry/window policy");
    }
    private static int value(Environment env, String name, int fallback) {
        int value = env.getProperty("app.activation." + name, Integer.class, fallback);
        if (value < 1) throw new IllegalArgumentException("Invalid activation policy: " + name);
        return value;
    }
}
