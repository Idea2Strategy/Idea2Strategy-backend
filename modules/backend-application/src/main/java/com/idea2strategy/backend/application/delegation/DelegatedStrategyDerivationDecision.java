package com.idea2strategy.backend.application.delegation;

@FunctionalInterface
public interface DelegatedStrategyDerivationDecision {
    DelegatedStrategyDerivationMutation decide();
}
