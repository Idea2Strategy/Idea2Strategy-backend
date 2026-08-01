package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.CompiledFlowPlan;

public interface CompiledFlowPlanCommandPort {
    CompiledFlowPlan saveOrFind(CompiledFlowPlan candidate);
}
