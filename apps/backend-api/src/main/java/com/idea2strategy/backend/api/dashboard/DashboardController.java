package com.idea2strategy.backend.api.dashboard;

import com.idea2strategy.backend.application.dashboard.DashboardQueryService;
import com.idea2strategy.backend.application.dashboard.DashboardSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@ConditionalOnBean(DashboardQueryService.class)
public class DashboardController {
    private final DashboardQueryService queryService;

    public DashboardController(DashboardQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public DashboardSnapshot getDashboard() {
        return queryService.getOwnedSnapshot();
    }
}
