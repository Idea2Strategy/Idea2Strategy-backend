package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandService;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/strategies")
@ConditionalOnBean(BasicStrategyDraftCommandService.class)
public class StrategyDraftController {
    private final BasicStrategyDraftCommandService commandService;

    public StrategyDraftController(BasicStrategyDraftCommandService commandService) {
        this.commandService = commandService;
    }

    @PostMapping
    public ResponseEntity<CreateStrategyResponse> create(@RequestBody CreateStrategyRequest request) {
        if (request.name() == null) {
            throw new IllegalArgumentException("Strategy name is required");
        }
        if (request.mode() != StrategyMode.BASIC) {
            throw new IllegalArgumentException("Only BASIC strategy drafts can be created");
        }
        UUID strategyId = commandService.createBasic(request.name(), request.description());
        return ResponseEntity.created(URI.create("/api/v1/strategies/" + strategyId))
                .body(new CreateStrategyResponse(strategyId, StrategyMode.BASIC));
    }

    public record CreateStrategyRequest(String name, String description, StrategyMode mode) {}

    public record CreateStrategyResponse(UUID id, StrategyMode mode) {}
}
