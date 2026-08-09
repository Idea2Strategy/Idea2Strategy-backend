package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.CompetitionRoom;

public interface OfficialBacktestRoomCommandPort {
    void save(CompetitionRoom room, BacktestEvaluationPlanDefinition plan);
}
