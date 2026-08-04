package com.idea2strategy.backend.admin;

import com.idea2strategy.backend.operatortrust.OperatorTrustModuleConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(OperatorTrustModuleConfiguration.class)
public class AdminMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdminMcpApplication.class, args);
    }
}
