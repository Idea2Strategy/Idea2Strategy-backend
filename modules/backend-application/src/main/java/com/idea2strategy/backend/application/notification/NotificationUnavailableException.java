package com.idea2strategy.backend.application.notification;

public final class NotificationUnavailableException extends RuntimeException {
    public NotificationUnavailableException() {
        super("NOTIFICATION_NOT_AVAILABLE");
    }
}
