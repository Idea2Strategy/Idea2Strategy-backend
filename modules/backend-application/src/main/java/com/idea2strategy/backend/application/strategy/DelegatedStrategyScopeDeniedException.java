package com.idea2strategy.backend.application.strategy;

/** The delegation does not carry an active scope for the requested edit. */
public final class DelegatedStrategyScopeDeniedException extends DelegatedBasicEditRejectedException {
    public DelegatedStrategyScopeDeniedException(String message) {
        super(message);
    }
}
