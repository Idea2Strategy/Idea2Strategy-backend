package com.idea2strategy.backend.application.notification;

public interface NotificationPolicyPort {
    NotificationPolicy requireActive(String typeCode);
}
