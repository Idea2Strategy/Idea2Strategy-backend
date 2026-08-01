package com.idea2strategy.backend.migration;

import java.util.List;

public record MigrationPlan(List<String> orderedFileNames) {
    public MigrationPlan {
        orderedFileNames = List.copyOf(orderedFileNames);
    }
}
