package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.evaluation.intake.ChatEvalDraftModel;

import static org.junit.Assert.assertEquals;

/** Locks Draft provenance to the actual Agent model and sampling configuration. */
public class EvaluationAdapterVersionTest {

    @Test
    public void draftVersionIncludesAgentModelAndTemperature() {
        ChatEvalDraftModel model = new ChatEvalDraftModel(null, "300013", "gpt-5.5", 0D);

        assertEquals("chat-agent:300013:model=gpt-5.5:temperature=0.0", model.version());
    }
}
