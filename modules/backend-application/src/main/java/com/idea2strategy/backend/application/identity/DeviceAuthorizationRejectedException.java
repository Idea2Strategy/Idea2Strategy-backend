package com.idea2strategy.backend.application.identity;

public class DeviceAuthorizationRejectedException extends RuntimeException {
    public DeviceAuthorizationRejectedException(String message) {
        super(message);
    }
}
