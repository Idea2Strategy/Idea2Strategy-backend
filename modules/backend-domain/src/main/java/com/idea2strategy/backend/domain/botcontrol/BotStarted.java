package com.idea2strategy.backend.domain.botcontrol;

import java.time.Instant;
import java.util.UUID;

public record BotStarted(UUID botId, UUID ownerAccountId, Instant occurredAt) {}
