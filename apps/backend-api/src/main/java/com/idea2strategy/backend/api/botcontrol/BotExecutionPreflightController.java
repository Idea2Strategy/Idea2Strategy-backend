package com.idea2strategy.backend.api.botcontrol;

import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightIssue;
import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bots")
@ConditionalOnBean(BotExecutionPreflightService.class)
public class BotExecutionPreflightController {
    private final BotExecutionPreflightService service;

    public BotExecutionPreflightController(BotExecutionPreflightService service) {
        this.service = service;
    }

    @GetMapping("/{botId}/preflight")
    public Response validate(@PathVariable UUID botId) {
        var report = service.validate(botId);
        return new Response(report.botId(), report.ready(), report.issues());
    }

    public record Response(UUID botId, boolean ready, List<BotExecutionPreflightIssue> issues) {}
}
