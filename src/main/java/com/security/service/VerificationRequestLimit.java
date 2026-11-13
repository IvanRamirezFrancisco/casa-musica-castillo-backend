package com.security.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.security.exception.AuthFlowException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class VerificationRequestLimit {
    private final Cache<String, Integer> requests = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(5)).build();

    public void check(String operation, String address, int maximum) {
        long window = System.currentTimeMillis() / Duration.ofMinutes(5).toMillis();
        requests.asMap().compute(operation + ":" + address + ":" + window, (key, count) -> {
            if (count != null && count >= maximum) {
                throw new AuthFlowException(HttpStatus.TOO_MANY_REQUESTS, "Demasiadas solicitudes. Intenta nuevamente en unos minutos.");
            }
            return count == null ? 1 : count + 1;
        });
    }
}
