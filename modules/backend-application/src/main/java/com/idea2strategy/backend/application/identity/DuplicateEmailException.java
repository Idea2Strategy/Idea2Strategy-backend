package com.idea2strategy.backend.application.identity;

public final class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException() {
        super("Email is already registered");
    }
}
