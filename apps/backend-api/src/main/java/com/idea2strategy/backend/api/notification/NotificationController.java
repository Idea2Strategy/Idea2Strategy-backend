package com.idea2strategy.backend.api.notification;

import com.idea2strategy.backend.api.identity.CustomerAccessPrincipal;
import com.idea2strategy.backend.application.notification.NotificationChannel;
import com.idea2strategy.backend.application.notification.NotificationPreferenceService;
import com.idea2strategy.backend.application.notification.NotificationPreferenceView;
import com.idea2strategy.backend.application.notification.NotificationQueryPort.NotificationPage;
import com.idea2strategy.backend.application.notification.NotificationQueryService;
import com.idea2strategy.backend.application.notification.NotificationService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account/notifications")
@ConditionalOnProperty(prefix = "identity.crypto", name = {
        "email-encryption-key", "lookup-hmac-key", "verification-hmac-key", "refresh-token-hmac-key",
        "customer-jwt-signing-key"})
public class NotificationController {
    private final NotificationQueryService queries;
    private final NotificationService notifications;
    private final NotificationPreferenceService preferences;
    private final CustomerAccessPrincipal principal;

    public NotificationController(
            NotificationQueryService queries,
            NotificationService notifications,
            NotificationPreferenceService preferences,
            CustomerAccessPrincipal principal) {
        this.queries = queries;
        this.notifications = notifications;
        this.preferences = preferences;
        this.principal = principal;
    }

    @GetMapping
    public NotificationPage list(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(required = false) Instant beforeCreatedAt,
            @RequestParam(required = false) UUID beforeId,
            @RequestParam(defaultValue = "20") int limit) {
        UUID accountId = accountId(authorization, correlationId);
        return queries.list(accountId, beforeCreatedAt, beforeId, limit);
    }

    @PutMapping("/{notificationId}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable UUID notificationId,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        notifications.markRead(accountId(authorization, correlationId), notificationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    public List<NotificationPreferenceView> preferences(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return preferences.list(accountId(authorization, correlationId));
    }

    @PutMapping("/preferences/{typeCode}")
    public NotificationPreferenceView replacePreference(
            @PathVariable String typeCode,
            @RequestBody ReplacePreferenceRequest request,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return preferences.replace(
                accountId(authorization, correlationId), typeCode, request.enabledChannels());
    }

    private UUID accountId(String authorization, String correlationId) {
        return principal.accountId();
    }

    public record ReplacePreferenceRequest(Set<NotificationChannel> enabledChannels) {}
}
