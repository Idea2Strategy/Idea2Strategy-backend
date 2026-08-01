package com.idea2strategy.backend.application.strategy;

import java.util.NoSuchElementException;

public final class StrategyCatalogNotFoundException extends NoSuchElementException {
    public StrategyCatalogNotFoundException() {
        super("Published strategy catalog not found");
    }
}
