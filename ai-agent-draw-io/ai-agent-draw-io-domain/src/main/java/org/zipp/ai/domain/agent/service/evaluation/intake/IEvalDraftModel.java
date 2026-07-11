package org.zipp.ai.domain.agent.service.evaluation.intake;

/** Model boundary for schema-constrained Trace-to-Eval draft generation. */
public interface IEvalDraftModel {
    String generate(String sanitizedPrompt);
    String version();
}
