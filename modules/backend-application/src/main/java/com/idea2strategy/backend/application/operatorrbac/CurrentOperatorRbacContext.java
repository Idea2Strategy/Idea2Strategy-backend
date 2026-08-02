package com.idea2strategy.backend.application.operatorrbac;

import java.util.Optional;

public interface CurrentOperatorRbacContext {
    Optional<OperatorRequestContext> current();
}
