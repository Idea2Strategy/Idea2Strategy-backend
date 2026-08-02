package com.idea2strategy.backend.application.accountretention;

public record RetentionBatchResult(int inspected, int completed, int held, int failed) {}
