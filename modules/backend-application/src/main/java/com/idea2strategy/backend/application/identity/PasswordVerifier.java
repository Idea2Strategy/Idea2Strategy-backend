package com.idea2strategy.backend.application.identity;

@FunctionalInterface
public interface PasswordVerifier {
    boolean matches(String rawPassword, String encodedPassword);
}
