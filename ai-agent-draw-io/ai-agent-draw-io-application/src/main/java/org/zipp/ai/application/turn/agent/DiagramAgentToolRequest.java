package org.zipp.ai.application.turn.agent;

/** Closed action space exposed to the Plain decision model. */
public sealed interface DiagramAgentToolRequest
        permits CreateDraftRequest, PatchDraftRequest, InspectDraftRequest {

    String toolName();
}
