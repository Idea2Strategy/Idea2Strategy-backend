package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PublicRoomSearchPort {
    List<PublicRoomItem> search(
            String nameQuery, Instant beforeCreatedAt, UUID beforeId, int limit);
}
