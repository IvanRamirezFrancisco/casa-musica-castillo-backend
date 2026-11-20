package com.security.service;

import com.security.exception.ActivationLimitException;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

@Component
public class VerificationRequestLimit {
    private final JdbcTemplate jdbc;
    private final ActivationCrypto crypto;
    private final Clock clock;
    private final ActivationClientAddress addresses;
    private final ActivationPolicy policy;
    public VerificationRequestLimit(JdbcTemplate jdbc, ActivationCrypto crypto, Clock clock,
                                    ActivationClientAddress addresses, ActivationPolicy policy) {
        this.jdbc = jdbc; this.crypto = crypto; this.clock = clock; this.addresses = addresses; this.policy = policy;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkClient(String operation, jakarta.servlet.http.HttpServletRequest request) {
        int maximum = switch (operation) {
            case "registration" -> policy.registerIp;
            case "resend-activation" -> policy.resendIp;
            default -> policy.verifyIp;
        };
        check(operation, addresses.resolve(request), maximum);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void check(String operation, String address, int maximum) {
        if (!reserve(operation, address, maximum, 300))
            throw new ActivationLimitException(300 - Math.floorMod(clock.instant().getEpochSecond(), 300));
    }
    /** Caller groups recipient/global reservations with challenge creation in one transaction. */
    public boolean reserve(String operation, String address, int maximum, int seconds) {
        long now = clock.instant().getEpochSecond(), start = now - Math.floorMod(now, seconds);
        return !jdbc.query("""
            INSERT INTO auth.activation_request_limits(scope,subject_digest,window_start,expires_at,requests)
            VALUES (?,?,?,?,1) ON CONFLICT(scope,subject_digest,window_start)
            DO UPDATE SET requests = activation_request_limits.requests + 1
            WHERE activation_request_limits.requests < ? RETURNING requests
            """, (rs, index) -> rs.getInt(1), operation, crypto.subject(operation + ":" + address),
            start, start + seconds, maximum).isEmpty();
    }
    /** A denied global budget must not consume the recipient's daily allowance. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserveDelivery(String address) {
        return reserveDeliveryFor("recipient-day", address);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserveDeliveryFor(String recipientScope, String address) {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            var savepoint = connection.setSavepoint();
            try {
                boolean accepted = reserve(recipientScope, address, policy.daily, 86400)
                    && reserve("delivery-minute", "global", policy.globalMinute, 60)
                    && reserve("delivery-day", "global", policy.globalDaily, 86400);
                if (!accepted) connection.rollback(savepoint);
                return accepted;
            } finally {
                connection.releaseSavepoint(savepoint);
            }
        }));
    }
    public void cleanup() {
        jdbc.update("DELETE FROM auth.activation_request_limits WHERE (scope,subject_digest,window_start) IN " +
            "(SELECT scope,subject_digest,window_start FROM auth.activation_request_limits WHERE expires_at <= ? LIMIT 10000)",
            clock.instant().getEpochSecond());
    }
}
