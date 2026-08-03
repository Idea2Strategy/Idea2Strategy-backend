package com.idea2strategy.backend.operatortrust;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Emits no token, subject, digest, permission, or provider payload. */
final class Slf4jOperatorAuthenticationEventSink implements OperatorAuthenticationEventSink {
    private static final Logger LOG = LoggerFactory.getLogger(Slf4jOperatorAuthenticationEventSink.class);

    @Override
    public void rejected(UUID correlationId, String reasonCode) {
        LOG.warn("operator_authentication_rejected correlation_id={} reason_code={}",
                correlationId, reasonCode);
    }
}
