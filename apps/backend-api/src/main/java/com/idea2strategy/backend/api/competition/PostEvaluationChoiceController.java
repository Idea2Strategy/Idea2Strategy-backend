package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.PostEvaluationAction;
import com.idea2strategy.backend.application.competition.PostEvaluationChoice;
import com.idea2strategy.backend.application.competition.UserPostEvaluationChoiceService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/competition/rooms/{roomId}/participations/{participationId}/post-evaluation-choice")
@ConditionalOnBean(UserPostEvaluationChoiceService.class)
public class PostEvaluationChoiceController {
    private final UserPostEvaluationChoiceService service;

    public PostEvaluationChoiceController(UserPostEvaluationChoiceService service) {
        this.service = service;
    }

    @GetMapping
    public ChoiceResponse find(@PathVariable UUID roomId, @PathVariable UUID participationId) {
        return response(service.find(roomId, participationId));
    }

    @PutMapping
    public ChoiceResponse update(
            @PathVariable UUID roomId,
            @PathVariable UUID participationId,
            @RequestBody ChoiceRequest request) {
        return response(service.update(roomId, participationId, request.action()));
    }

    public record ChoiceRequest(PostEvaluationAction action) {}

    public record ChoiceResponse(
            UUID roomId,
            UUID participationId,
            PostEvaluationAction action,
            Instant recordedAt,
            Instant lockedAt) {}

    private static ChoiceResponse response(PostEvaluationChoice choice) {
        return new ChoiceResponse(
                choice.roomId(),
                choice.participationId(),
                choice.action(),
                choice.recordedAt(),
                choice.lockedAt());
    }
}
