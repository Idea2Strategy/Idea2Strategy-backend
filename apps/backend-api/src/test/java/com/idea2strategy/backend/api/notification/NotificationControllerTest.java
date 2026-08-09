package com.idea2strategy.backend.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.api.identity.CustomerAccessPrincipal;
import com.idea2strategy.backend.application.notification.EmailNotificationPreferenceService;
import com.idea2strategy.backend.application.notification.EmailNotificationPreferenceView;
import com.idea2strategy.backend.application.notification.NotificationQueryPort.NotificationPage;
import com.idea2strategy.backend.application.notification.NotificationQueryService;
import com.idea2strategy.backend.application.notification.NotificationService;
import java.util.List;
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
        var preferences = mock(EmailNotificationPreferenceService.class);
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
    void emailPreferenceReadAndUpdateUseOnlyTheAuthenticatedAccount() {
        var queries = mock(NotificationQueryService.class);
        var notifications = mock(NotificationService.class);
        var preferences = mock(EmailNotificationPreferenceService.class);
        var principal = mock(CustomerAccessPrincipal.class);
        UUID correlation = UUID.randomUUID();
        when(principal.accountId()).thenReturn(ACCOUNT);
        var controller = new NotificationController(queries, notifications, preferences, principal);

        when(preferences.get(ACCOUNT)).thenReturn(new EmailNotificationPreferenceView(false));
        when(preferences.replace(ACCOUNT, true)).thenReturn(new EmailNotificationPreferenceView(true));

        assertThat(controller.emailPreference("Bearer token", correlation.toString()).enabled()).isFalse();
        assertThat(controller.replaceEmailPreference(
                new NotificationController.ReplaceEmailPreferenceRequest(true),
                "Bearer token", correlation.toString()).enabled()).isTrue();

        verify(preferences).get(ACCOUNT);
        verify(preferences).replace(ACCOUNT, true);
    }
}
