package com.idea2strategy.backend.api.botcontrol;

import com.idea2strategy.backend.application.botcontrol.BotRunCommandService;
import com.idea2strategy.backend.application.botcontrol.BotRunDispatch;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bots")
@ConditionalOnBean(BotRunCommandService.class)
public class BotRunCommandController {
    private final BotRunCommandService service;

    public BotRunCommandController(BotRunCommandService service) {
        this.service = service;
    }

    @PostMapping("/{botId}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BotRunDispatch run(@PathVariable UUID botId) {
        return service.issue(botId);
    }
}
