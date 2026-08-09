package com.idea2strategy.backend.application.usercase;

import java.util.List;

public record UserCasePage(List<UserCaseSummary> items, String nextCursor) {
    public UserCasePage {
        items = List.copyOf(items);
    }
}
