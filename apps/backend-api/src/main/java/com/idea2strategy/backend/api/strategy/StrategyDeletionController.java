package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.strategy.StrategyDeletionCommandService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/strategies")
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.customer-jwt-signing-key"})
public class StrategyDeletionController {
    private final StrategyDeletionCommandService commandService;

    public StrategyDeletionController(StrategyDeletionCommandService commandService) {
        this.commandService = commandService;
    }

    @DeleteMapping("/{strategyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID strategyId) {
        commandService.delete(strategyId);
    }
}
