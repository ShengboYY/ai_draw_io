package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTargetMigrationStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvaluationTargetInference;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Infers only strong legacy signals; uncertain Cases remain blocked for human confirmation. */
@Service
public class EvaluationTargetInferenceService {

    public EvaluationTargetInference infer(EvalCaseDefinition definition) {
        if (definition == null) return ambiguous("definition is missing");
        if (definition.getEvaluationTarget() != null) {
            return new EvaluationTargetInference(definition.getEvaluationTarget(),
                    EvaluationTargetMigrationStatus.CONFIRMED, "explicit evaluationTarget");
        }
        Set<String> tags = definition.getTags() == null ? Set.of() : definition.getTags().stream()
                .filter(tag -> tag != null).map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (tags.contains("target:ambiguous")) return ambiguous("source requires administrator confirmation");
        Set<EvaluationTarget> tagged = targetsFromTags(tags);
        if (tagged.size() == 1) return inferred(tagged.iterator().next(), "explicit legacy target tag");
        if (tagged.size() > 1) return ambiguous("legacy target tags conflict");

        // fixture-v1 is the recorded full router-to-tool replay contract used by the legacy harness.
        if ("fixture-v1".equalsIgnoreCase(definition.getFixtureVersion())) {
            return inferred(EvaluationTarget.FULL_AGENT, "legacy fixture-v1 replay");
        }
        EvalCaseDefinition.Expected expected = definition.getExpected();
        boolean drawing = expected != null && (expected.getGraph() != null
                || expected.getMaxCriticalIssues() != null || expected.getMaxMajorIssues() != null);
        boolean router = expected != null && expected.getRouteType() != null
                && !expected.getRouteType().isBlank();
        if (drawing && !router) return inferred(EvaluationTarget.DRAWING_QUALITY, "drawing-only assertions");
        if (router && !drawing) return inferred(EvaluationTarget.INTENT_ROUTER, "route-only assertion");
        return ambiguous("legacy signals overlap or are insufficient");
    }

    private Set<EvaluationTarget> targetsFromTags(Set<String> tags) {
        java.util.EnumSet<EvaluationTarget> targets = java.util.EnumSet.noneOf(EvaluationTarget.class);
        if (matches(tags, "target:full_agent")) targets.add(EvaluationTarget.FULL_AGENT);
        if (matches(tags, "target:intent_router")) targets.add(EvaluationTarget.INTENT_ROUTER);
        if (matches(tags, "target:drawing_quality")) targets.add(EvaluationTarget.DRAWING_QUALITY);
        return targets;
    }

    private boolean matches(Set<String> tags, String... values) {
        for (String value : values) if (tags.contains(value)) return true;
        return false;
    }

    private EvaluationTargetInference inferred(EvaluationTarget target, String reason) {
        return new EvaluationTargetInference(target, EvaluationTargetMigrationStatus.INFERRED, reason);
    }

    private EvaluationTargetInference ambiguous(String reason) {
        return new EvaluationTargetInference(null, EvaluationTargetMigrationStatus.AMBIGUOUS, reason);
    }
}
