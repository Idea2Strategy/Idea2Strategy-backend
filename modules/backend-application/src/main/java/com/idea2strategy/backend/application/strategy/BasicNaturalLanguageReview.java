package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.TradeContainer;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record BasicNaturalLanguageReview(
        List<GroupReview> groups,
        List<BasicBlockAssemblyIssue> issues) {
    public BasicNaturalLanguageReview {
        groups = List.copyOf(groups);
        issues = List.copyOf(issues);
    }

    public boolean translatable() {
        return issues.isEmpty();
    }

    public record GroupReview(
            String groupId,
            TradeContainer container,
            List<BlockReview> blocks) {
        public GroupReview {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(container, "container");
            blocks = List.copyOf(blocks);
        }

        public String sentence() {
            return blocks.stream().map(BlockReview::text).collect(Collectors.joining(" "));
        }
    }

    public record BlockReview(String blockId, String text) {
        public BlockReview {
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(text, "text");
        }
    }
}
