package com.idea2strategy.backend.domain.botcontrol;

import java.time.Instant;
import java.util.UUID;

public record BotStopRequested(UUID botId, UUID ownerAccountId, String reasonCode, Instant occurredAt) {}
