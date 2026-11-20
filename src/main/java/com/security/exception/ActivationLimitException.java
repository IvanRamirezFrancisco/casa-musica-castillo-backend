package com.security.exception;
import org.springframework.http.HttpStatus;
public class ActivationLimitException extends AuthFlowException {
    private final long retryAfter;
    public ActivationLimitException(long retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Demasiadas solicitudes. Intenta nuevamente más tarde.");
        this.retryAfter = Math.max(1, retryAfter);
    }
    public long getRetryAfter() { return retryAfter; }
}
