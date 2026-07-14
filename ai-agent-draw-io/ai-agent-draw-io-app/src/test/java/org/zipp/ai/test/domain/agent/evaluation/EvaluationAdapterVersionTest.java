package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.evaluation.intake.ChatEvalDraftModel;

import static org.junit.Assert.assertEquals;

/** Locks Draft provenance to the actual Agent model and sampling configuration. */
public class EvaluationAdapterVersionTest {

    @Test
    public void draftVersionIncludesProviderAgentModelAndTemperature() {
        ChatEvalDraftModel model = new ChatEvalDraftModel(null, "deepseek-api-v1", "300013", "deepseek-chat", 0D);

        assertEquals("provider=deepseek-api-v1:chat-agent=300013:model=deepseek-chat:temperature=0.0", model.version());
    }
}
