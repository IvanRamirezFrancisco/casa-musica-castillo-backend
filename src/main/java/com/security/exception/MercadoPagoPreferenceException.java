package com.security.exception;

public class MercadoPagoPreferenceException extends RuntimeException {
    
    private final Integer providerStatusCode;
    private final String providerError;
    private final String safeMessage;

    public MercadoPagoPreferenceException(String safeMessage, Integer providerStatusCode, String providerError, Throwable cause) {
        super(safeMessage, cause);
        this.safeMessage = safeMessage;
        this.providerStatusCode = providerStatusCode;
        this.providerError = providerError;
    }

    public MercadoPagoPreferenceException(String safeMessage, Throwable cause) {
        this(safeMessage, null, null, cause);
    }

    public MercadoPagoPreferenceException(String safeMessage) {
        this(safeMessage, null, null, null);
    }

    public Integer getProviderStatusCode() {
        return providerStatusCode;
    }

    public String getProviderError() {
        return providerError;
    }

    public String getSafeMessage() {
        return safeMessage;
    }
}
