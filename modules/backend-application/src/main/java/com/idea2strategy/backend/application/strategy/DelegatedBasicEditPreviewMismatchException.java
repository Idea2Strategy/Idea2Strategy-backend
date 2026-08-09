package com.idea2strategy.backend.application.strategy;

/** The reviewed preview hash does not describe the edit being applied. */
public final class DelegatedBasicEditPreviewMismatchException extends DelegatedBasicEditRejectedException {
    public DelegatedBasicEditPreviewMismatchException(String message) {
        super(message);
    }
}
