package com.idea2strategy.backend.application.competition;

import java.time.Instant;

public record PrivateContinuationTransitionReport(Instant observedAt, int transitionsApplied) {}
