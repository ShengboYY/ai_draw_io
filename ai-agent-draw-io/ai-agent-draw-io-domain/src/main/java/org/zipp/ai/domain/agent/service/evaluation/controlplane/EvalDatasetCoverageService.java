package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Computes transparent raw coverage counts from pinned published Case definitions. */
@Service
public class EvalDatasetCoverageService {
    private final EvalDatasetService datasets;
    private final EvalCasePublisherService cases;

    public EvalDatasetCoverageService(EvalDatasetService datasets, EvalCasePublisherService cases) {
        this.datasets = datasets; this.cases = cases;
    }

    public EvalDatasetCoverage coverage(String datasetId, String version, EvalAdminRole role) {
        EvalDatasetVersion dataset = datasets.get(datasetId, version);
        if (dataset.getDatasetClass() == EvalDatasetClass.SEQUESTERED && role != EvalAdminRole.RELEASE_OWNER) {
            throw new SecurityException("sequestered dataset contents require Release Owner role");
        }
        Map<String, Integer> routes = new LinkedHashMap<>();
        Map<String, Integer> risks = new LinkedHashMap<>();
        Map<String, Integer> languages = new LinkedHashMap<>();
        Map<String, Integer> diagrams = new LinkedHashMap<>();
        Map<String, Integer> agents = new LinkedHashMap<>();
        for (EvalDatasetMember member : dataset.getMembers()) {
            EvalCaseDefinition definition = cases.load(member.getCaseId(), member.getCaseVersion());
            increment(routes, definition.getExpected() == null ? null : definition.getExpected().getRouteType());
            increment(risks, definition.getRisk());
            increment(diagrams, definition.getDiagramType());
            increment(languages, tagValue(definition, "lang:", "unknown"));
            increment(agents, tagValue(definition, "agent:", "router+drawing"));
        }
        return EvalDatasetCoverage.builder().caseCount(dataset.getMembers().size()).routes(routes).risks(risks)
                .languages(languages).diagramTypes(diagrams).agents(agents).build();
    }

    private String tagValue(EvalCaseDefinition definition, String prefix, String fallback) {
        if (definition.getTags() == null) return fallback;
        return definition.getTags().stream().filter(tag -> tag != null && tag.startsWith(prefix))
                .map(tag -> tag.substring(prefix.length())).findFirst().orElse(fallback);
    }

    private void increment(Map<String, Integer> values, String key) {
        values.merge(key == null || key.isBlank() ? "unknown" : key, 1, Integer::sum);
    }
}
