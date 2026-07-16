package org.zipp.ai.domain.agent.model.valobj.canvas;

import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;

public record CanvasMutationCommand(
        CanvasMutationPurpose purpose,
        String currentXml,
        String candidateXml,
        DiagramType diagramType,
        CanvasMutationAuthorization authorization,
        String userId,
        String diagramId,
        Long expectedVersion,
        String expectedContentHash
) {
}
