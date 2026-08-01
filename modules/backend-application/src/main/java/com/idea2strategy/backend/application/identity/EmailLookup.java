package com.idea2strategy.backend.application.identity;

@FunctionalInterface
public interface EmailLookup {
    String lookup(String rawEmail);
}
