package com.idea2strategy.backend.persistence.botcontrol;

import com.idea2strategy.backend.application.botcontrol.BotCommandPort;
import com.idea2strategy.backend.domain.botcontrol.Bot;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BotJpaCommandAdapter implements BotCommandPort {
    private final BotSpringDataRepository repository;

    public BotJpaCommandAdapter(BotSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(Bot bot) {
        repository.saveAndFlush(BotJpaEntity.from(bot));
    }
}
