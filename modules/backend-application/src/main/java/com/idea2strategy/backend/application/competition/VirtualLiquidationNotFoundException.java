package com.idea2strategy.backend.application.competition;

public final class VirtualLiquidationNotFoundException extends RuntimeException {
    public VirtualLiquidationNotFoundException() {
        super("Official live evaluation segment was not found");
    }
}
