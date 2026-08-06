package com.idea2strategy.backend.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.api.identity.CustomerAccessPrincipal;
import com.idea2strategy.backend.application.notification.NotificationChannel;
import com.idea2strategy.backend.application.notification.NotificationPreferenceService;
import com.idea2strategy.backend.application.notification.NotificationQueryPort.NotificationPage;
import com.idea2strategy.backend.application.notification.NotificationQueryService;
import com.idea2strategy.backend.application.notification.NotificationService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationControllerTest {
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-4000-8000-000000000018");
    private static final UUID SESSION = UUID.fromString("20000000-0000-4000-8000-000000000018");
    private static final UUID NOTIFICATION = UUID.fromString("30000000-0000-4000-8000-000000000018");

    @Test
    void listAndMarkReadUseOnlyTheAuthenticatedAccount() {
        var queries = mock(NotificationQueryService.class);
        var notifications = mock(NotificationService.class);
        var preferences = mock(NotificationPreferenceService.class);
        var principal = mock(CustomerAccessPrincipal.class);
        UUID correlation = UUID.randomUUID();
        when(principal.accountId()).thenReturn(ACCOUNT);
        when(queries.list(ACCOUNT, null, null, 20))
                .thenReturn(new NotificationPage(List.of(), null, null));
        var controller = new NotificationController(queries, notifications, preferences, principal);

        assertThat(controller.list("Bearer token", correlation.toString(), null, null, 20).items())
                .isEmpty();
        assertThat(controller.markRead(NOTIFICATION, "Bearer token", correlation.toString()).getStatusCode().value())
                .isEqualTo(204);

        verify(queries).list(ACCOUNT, null, null, 20);
        verify(notifications).markRead(ACCOUNT, NOTIFICATION);
    }

    @Test
    void preferenceUpdateUsesOnlyTheAuthenticatedAccount() {
        var queries = mock(NotificationQueryService.class);
        var notifications = mock(NotificationService.class);
        var preferences = mock(NotificationPreferenceService.class);
        var principal = mock(CustomerAccessPrincipal.class);
        UUID correlation = UUID.randomUUID();
        when(principal.accountId()).thenReturn(ACCOUNT);
        var controller = new NotificationController(queries, notifications, preferences, principal);

        controller.replacePreference(
                "BOT_SUMMARY",
                new NotificationController.ReplacePreferenceRequest(Set.of(NotificationChannel.APP)),
                "Bearer token",
                correlation.toString());

        verify(preferences).replace(ACCOUNT, "BOT_SUMMARY", Set.of(NotificationChannel.APP));
    }
}
