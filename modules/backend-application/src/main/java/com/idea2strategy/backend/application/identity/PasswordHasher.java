package com.idea2strategy.backend.application.identity;

@FunctionalInterface
public interface PasswordHasher {
    PasswordHash hash(String rawPassword);
}
