package com.idea2strategy.backend.application.marketdata;

import java.util.UUID;

public final class UnsupportedMarketInstrumentException extends RuntimeException {
    public UnsupportedMarketInstrumentException(UUID instrumentId) {
        super("Instrument is not available in the published strategy catalog: " + instrumentId);
    }
}
