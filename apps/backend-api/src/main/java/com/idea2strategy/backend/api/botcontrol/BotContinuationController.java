package com.idea2strategy.backend.api.botcontrol;

import com.idea2strategy.backend.application.botcontrol.BotContinuationService;
import com.idea2strategy.backend.application.botcontrol.BotContinuationView;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bots")
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.customer-jwt-signing-key"})
public class BotContinuationController {
    private final BotContinuationService service;

    public BotContinuationController(BotContinuationService service) {
        this.service = service;
    }

    @GetMapping("/{botId}/continuation")
    public BotContinuationView get(@PathVariable UUID botId) {
        return service.get(botId);
    }

    @PostMapping("/{botId}/continuation/renew")
    public BotContinuationView renew(@PathVariable UUID botId) {
        return service.renew(botId);
    }
}
