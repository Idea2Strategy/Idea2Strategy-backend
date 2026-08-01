package com.idea2strategy.backend.application.identity;

@FunctionalInterface
public interface EmailProtector {
    ProtectedEmail protect(String rawEmail);
}
