package com.idea2strategy.backend.application.competition;

public interface VirtualLiquidationContextPort {
    VirtualLiquidationContext load(VirtualLiquidationRequest request);
}
