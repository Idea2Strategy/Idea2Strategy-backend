package com.idea2strategy.backend.api;

import com.idea2strategy.backend.api.identity.RequestPrincipalConfiguration;
import com.idea2strategy.backend.api.operatorrbac.OperatorRequestContextConfiguration;
import com.idea2strategy.backend.api.operatorrbac.OperatorRbacConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({RequestPrincipalConfiguration.class, OperatorRequestContextConfiguration.class,
        OperatorRbacConfiguration.class})
public class BackendApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendApiApplication.class, args);
    }
}
