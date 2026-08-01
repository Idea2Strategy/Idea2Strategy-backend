package com.idea2strategy.backend.application.identity;

@FunctionalInterface
public interface PasswordPolicy {
    void validate(String rawPassword);
}
