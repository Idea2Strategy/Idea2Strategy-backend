package com.idea2strategy.backend.application.competition;

public record CompetitionDependencyReadiness(
        boolean botAvailable, boolean tradingAvailable, boolean backtestAvailable) {}
