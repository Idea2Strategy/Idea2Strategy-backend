package com.idea2strategy.backend.application.strategy;

public final class InvalidBasicStructureDefinitionException extends RuntimeException {
    public InvalidBasicStructureDefinitionException(String code, String reason) {
        super("Basic structure " + code + " is invalid: " + reason);
    }
}
