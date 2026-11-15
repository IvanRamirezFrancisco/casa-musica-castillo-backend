package com.security.exception;

import java.util.List;

public class PickupValidationException extends RuntimeException {
    private final List<String> details;

    public PickupValidationException(String message, List<String> details) {
        super(message);
        this.details = details;
    }

    public List<String> getDetails() {
        return details;
    }
}
