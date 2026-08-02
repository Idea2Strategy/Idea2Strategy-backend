package com.idea2strategy.backend.application.notification;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationQueryServiceTest {
    @Test
    void rejects_partial_cursor_before_querying_persistence() {
        var service = new NotificationQueryService((accountId, time, id, limit) -> {
            throw new AssertionError("must not query");
        });

        assertThrows(IllegalArgumentException.class,
                () -> service.list(UUID.randomUUID(), Instant.now(), null, 20));
    }
}
