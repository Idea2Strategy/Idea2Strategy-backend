package com.idea2strategy.backend.operatortrust;

import java.util.UUID;

/** Privacy-safe pre-authentication security evidence boundary. */
public interface OperatorAuthenticationEventSink {
    void rejected(UUID correlationId, String reasonCode);
}
