package com.idea2strategy.backend.application.strategy;

/**
 * A delegated Basic edit was refused.
 *
 * <p>Two refusals mean something different to the caller and carry their own subtype, because the
 * external tool contract maps them to distinct stable exit codes: scope denial says the delegation
 * never permitted this, while a preview mismatch says the reviewed diff is not the one being
 * applied. Every other refusal is an ordinary invalid operation and uses this type directly.
 */
public class DelegatedBasicEditRejectedException extends RuntimeException {
    public DelegatedBasicEditRejectedException(String message) {
        super(message);
    }
}
