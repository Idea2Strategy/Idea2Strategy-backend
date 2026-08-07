package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.strategy.StrategyCopyCommandService;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/strategies/{strategyId}/copies")
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.session-hmac-key"})
public class StrategyCopyController {
    private final StrategyCopyCommandService commandService;

    public StrategyCopyController(StrategyCopyCommandService commandService) {
        this.commandService = commandService;
    }

    @PostMapping
    public ResponseEntity<CopyStrategyResponse> copy(@PathVariable UUID strategyId) {
        UUID copyId = commandService.copyOwnedStrategy(strategyId);
        return ResponseEntity.created(URI.create("/api/v1/strategies/" + copyId))
                .body(new CopyStrategyResponse(copyId));
    }

    public record CopyStrategyResponse(UUID id) {}
}
