package org.zipp.ai.application.turn.agent;

/** The model may either interact with the draft environment or request terminal submission. */
public sealed interface DiagramAgentAction
        permits CallDiagramTool, SubmitDiagramCandidate {
}
