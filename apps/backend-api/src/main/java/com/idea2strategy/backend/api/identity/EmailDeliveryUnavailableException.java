package com.idea2strategy.backend.api.identity;

public final class EmailDeliveryUnavailableException extends RuntimeException {
    private final boolean retryable;

    public EmailDeliveryUnavailableException(boolean retryable) {
        super("Email delivery is unavailable");
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
