package com.idea2strategy.backend.api.backtest;

import com.idea2strategy.backend.application.backtest.BacktestRequestReceipt;
import com.idea2strategy.backend.application.backtest.CustomBacktestCommand;
import com.idea2strategy.backend.application.backtest.CustomBacktestService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bots/{botId}/backtests")
@ConditionalOnBean(CustomBacktestService.class)
public class CustomBacktestController {
    private final CustomBacktestService service;

    public CustomBacktestController(CustomBacktestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<BacktestRequestReceipt> request(
            @PathVariable UUID botId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CustomBacktestBody body) {
        var receipt = service.request(new CustomBacktestCommand(
                botId, body.periodStart(), body.periodEnd(), idempotencyKey));
        return ResponseEntity.accepted().body(receipt);
    }

    public record CustomBacktestBody(LocalDate periodStart, LocalDate periodEnd) {}
}
