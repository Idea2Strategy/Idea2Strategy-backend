package com.idea2strategy.backend.batch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude="
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
            + "org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration",
    "idea2strategy.batch.expired-bot-stop.enabled=false",
    "idea2strategy.batch.account-dormancy.enabled=false",
    "idea2strategy.batch.room-schedule-transition.enabled=false",
    "idea2strategy.batch.room-evaluation-start.enabled=false",
    "idea2strategy.batch.private-continuation-transition.enabled=false"
})
class BackendBatchApplicationTest {
    @Test
    void contextLoads() {
    }
}
