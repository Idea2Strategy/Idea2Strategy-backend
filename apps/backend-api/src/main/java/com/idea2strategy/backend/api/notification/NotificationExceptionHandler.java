package com.idea2strategy.backend.api.notification;

import com.idea2strategy.backend.application.notification.NotificationUnavailableException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = NotificationController.class)
public class NotificationExceptionHandler {
    @ExceptionHandler(NotificationUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(NotificationUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "NOTIFICATION_NOT_AVAILABLE"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_NOTIFICATION_REQUEST"));
    }
}
