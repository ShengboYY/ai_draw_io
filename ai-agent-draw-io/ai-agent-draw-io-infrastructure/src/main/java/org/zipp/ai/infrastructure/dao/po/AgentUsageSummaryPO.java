package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

@Data
public class AgentUsageSummaryPO {

    private Long platformRunCount;
    private Long userKeyRunCount;
    private Long platformLlmCallCount;
    private Long userKeyLlmCallCount;
    private Long toolCallCount;
    private Long knownTotalTokens;
    private Long unknownTokenLlmCallCount;
}
