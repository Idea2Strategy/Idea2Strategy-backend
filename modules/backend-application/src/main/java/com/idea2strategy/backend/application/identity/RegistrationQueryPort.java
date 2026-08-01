package com.idea2strategy.backend.application.identity;

@FunctionalInterface
public interface RegistrationQueryPort {
    boolean emailExists(String emailLookupHmac);
}
