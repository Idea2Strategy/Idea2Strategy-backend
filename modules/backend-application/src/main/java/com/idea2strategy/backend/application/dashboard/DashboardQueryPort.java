package com.idea2strategy.backend.application.dashboard;

import java.util.List;
import java.util.UUID;

public interface DashboardQueryPort {
    List<DashboardBotProjection> findOwned(UUID ownerAccountId);
}
