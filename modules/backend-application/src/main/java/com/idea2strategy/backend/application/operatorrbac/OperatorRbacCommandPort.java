package com.idea2strategy.backend.application.operatorrbac;

import java.time.Instant;

public interface OperatorRbacCommandPort {
    OperatorRbacResult executeAtomically(
            OperatorRbacCommand command,
            Instant evaluatedAt,
            OperatorRbacDecision decision);
}
