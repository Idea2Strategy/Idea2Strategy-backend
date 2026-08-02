package com.idea2strategy.backend.application.bottrading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The strategy budget, as the rebuildable canonical projections hold it. */
public record BotBudgetView(
        String currencyCode,
        BigDecimal availableCashAmount,
        BigDecimal activeReservationAmount,
        BigDecimal investedAmount,
        Instant valuationAt,
        String valuationStatus,
        long lastEventSequence,
        List<PartitionBudget> partitions) {

    /** One partition's share of the budget. */
    public record PartitionBudget(
            UUID partitionId,
            BigDecimal budgetCapAmount,
            BigDecimal activeReservationAmount,
            BigDecimal investedAmount) {}
}
