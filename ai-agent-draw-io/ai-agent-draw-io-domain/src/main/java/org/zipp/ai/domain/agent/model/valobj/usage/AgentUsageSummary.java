package org.zipp.ai.domain.agent.model.valobj.usage;

import lombok.Builder;
import lombok.Data;

/** Current-user usage summary for account screens. */
@Data
@Builder
public class AgentUsageSummary {

    @Builder.Default
    private Long platformRunCount = 0L;
    @Builder.Default
    private Long userKeyRunCount = 0L;
    @Builder.Default
    private Long platformLlmCallCount = 0L;
    @Builder.Default
    private Long userKeyLlmCallCount = 0L;
    @Builder.Default
    private Long toolCallCount = 0L;
    @Builder.Default
    private Long knownTotalTokens = 0L;
    @Builder.Default
    private Long unknownTokenLlmCallCount = 0L;

    public static AgentUsageSummary empty() {
        return AgentUsageSummary.builder().build();
    }
}
