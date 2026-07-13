package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Map;

/** Descriptive coverage counts only; it never fabricates classification metrics for thin strata. */
@Value
@Builder
public class EvalDatasetCoverage {
    int caseCount;
    @Singular("route") Map<String, Integer> routes;
    @Singular("risk") Map<String, Integer> risks;
    @Singular("language") Map<String, Integer> languages;
    @Singular("diagramType") Map<String, Integer> diagramTypes;
    @Singular("agent") Map<String, Integer> agents;
}
