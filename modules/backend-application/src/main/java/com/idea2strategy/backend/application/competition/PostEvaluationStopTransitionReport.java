package com.idea2strategy.backend.application.competition;

import java.time.Instant;

public record PostEvaluationStopTransitionReport(Instant observedAt, int transitionsApplied) {}
