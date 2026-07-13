package org.zipp.ai.domain.agent.service.evaluation.visual;

import java.util.List;

/** Production discovery port; it cannot approve a Case or score an Eval episode. */
public interface IVisualAnomalyMiner {
    Finding analyze(Input input);
    String version();

    record Input(IDiagramImageRenderer.RenderedDiagram image, List<String> analyzerEvidence, String diagramType) { }
    record Finding(boolean potentialIssue, double confidence, String issueFamily, List<String> evidence,
                   String suggestedRisk, String syntheticReconstructionSuggestion, boolean requiresHumanReview) { }
}
