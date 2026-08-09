package com.idea2strategy.backend.api.botoperations;

import com.idea2strategy.backend.application.botoperations.BotDeletionCommandService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bots")
@ConditionalOnBean(BotDeletionCommandService.class)
public class BotDeletionController {
    private final BotDeletionCommandService commandService;

    public BotDeletionController(BotDeletionCommandService commandService) {
        this.commandService = commandService;
    }

    @DeleteMapping("/{botId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID botId) {
        commandService.delete(botId);
    }
}
