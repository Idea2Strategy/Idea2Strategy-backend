package com.idea2strategy.backend.application.botcontrol;

import com.idea2strategy.backend.domain.botcontrol.Bot;

public interface BotCommandPort {
    void save(Bot bot);
}
