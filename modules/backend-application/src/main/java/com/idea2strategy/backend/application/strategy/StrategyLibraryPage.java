package com.idea2strategy.backend.application.strategy;

import java.util.List;

public record StrategyLibraryPage(List<StrategyLibraryItem> items, String nextCursor, boolean hasMore) {
    public StrategyLibraryPage {
        items = List.copyOf(items);
    }
}
