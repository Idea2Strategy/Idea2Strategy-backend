package com.idea2strategy.backend.application.strategy;

public final class UnsupportedStrategyElementException extends IllegalArgumentException {
    public UnsupportedStrategyElementException(String elementCode) {
        super("Unsupported strategy element: " + elementCode);
    }
}
