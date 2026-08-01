package com.idea2strategy.backend.application.competition;

import java.util.List;

public record PublicRoomPage(List<PublicRoomItem> items, String nextCursor, boolean hasMore) {
    public PublicRoomPage {
        items = List.copyOf(items);
    }
}
