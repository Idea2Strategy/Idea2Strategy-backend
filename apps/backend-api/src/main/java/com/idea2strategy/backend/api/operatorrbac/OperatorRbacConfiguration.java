package com.idea2strategy.backend.api.operatorrbac;

import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommandPort;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommandService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OperatorRbacConfiguration {
    @Bean
    @ConditionalOnBean(OperatorRbacCommandPort.class)
    @ConditionalOnMissingBean(OperatorRbacCommandService.class)
    OperatorRbacCommandService operatorRbacCommandService(OperatorRbacCommandPort port) {
        return new OperatorRbacCommandService(port, Clock.systemUTC());
    }
}
