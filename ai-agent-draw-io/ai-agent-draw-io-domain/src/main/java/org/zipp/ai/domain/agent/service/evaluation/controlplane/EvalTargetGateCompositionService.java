package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalEpisodeStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalGateDecisionRecord;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalGateOutcome;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalRun;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalRunMode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only CI adapter over immutable per-target Gate decisions. Composite suites stay
 * request-scoped until operators have a real need to version and persist them.
 */
@Service
public class EvalTargetGateCompositionService {
    private final IEvalRunStore runs;

    public EvalTargetGateCompositionService(IEvalRunStore runs) {
        this.runs = runs;
    }

    public Decision compose(Map<EvaluationTarget, String> requestedRuns, Set<EvaluationTarget> requiredTargets) {
        Map<EvaluationTarget, String> runIds = new EnumMap<>(EvaluationTarget.class);
        if (requestedRuns != null) runIds.putAll(requestedRuns);
        Set<EvaluationTarget> required = requiredTargets == null || requiredTargets.isEmpty()
                ? EnumSet.noneOf(EvaluationTarget.class) : EnumSet.copyOf(requiredTargets);
        if (required.isEmpty()) throw new IllegalArgumentException("at least one required target is required");

        Set<EvaluationTarget> selected = EnumSet.copyOf(required);
        selected.addAll(runIds.keySet());
        Map<EvaluationTarget, TargetDecision> targets = new LinkedHashMap<>();
        for (EvaluationTarget target : EvaluationTarget.values()) {
            if (selected.contains(target)) targets.put(target, target(target, runIds.get(target), required.contains(target)));
        }

        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        targets.values().stream().filter(TargetDecision::hardFailure)
                .forEach(value -> reasons.add(value.target() + " contains a deterministic hard failure"));
        if (!reasons.isEmpty()) return decision(EvalGateOutcome.BLOCK, targets, reasons, warnings);

        targets.values().stream().filter(value -> value.required() && value.outcome() == EvalGateOutcome.BLOCK)
                .forEach(value -> reasons.add(value.target() + " required Gate is BLOCK"));
        if (!reasons.isEmpty()) return decision(EvalGateOutcome.BLOCK, targets, reasons, warnings);

        targets.values().stream().filter(value -> value.required() && value.outcome() != EvalGateOutcome.PASS)
                .forEach(value -> reasons.add(value.target() + " required Gate is missing or NO_DECISION"));
        targets.values().stream().filter(value -> !value.required() && value.outcome() != EvalGateOutcome.PASS)
                .forEach(value -> warnings.add(value.target() + " optional Gate is " + value.outcome()));
        if (!reasons.isEmpty()) return decision(EvalGateOutcome.NO_DECISION, targets, reasons, warnings);
        return decision(EvalGateOutcome.PASS, targets, List.of("all required target Gates passed"), warnings);
    }

    private TargetDecision target(EvaluationTarget target, String runId, boolean required) {
        if (runId == null || runId.isBlank()) {
            return new TargetDecision(target, null, required, EvalGateOutcome.NO_DECISION, false,
                    false, List.of("target Run is not configured"));
        }
        EvalRun run = runs.findRun(runId).orElse(null);
        if (run == null) {
            return new TargetDecision(target, runId, required, EvalGateOutcome.NO_DECISION, false,
                    false, List.of("target Run was not found"));
        }
        if (run.getEvaluationTarget() != target) {
            return new TargetDecision(target, runId, required, EvalGateOutcome.NO_DECISION, false,
                    false, List.of("Run target does not match " + target));
        }
        if (run.getMode() != EvalRunMode.RELEASE) {
            return new TargetDecision(target, runId, required, EvalGateOutcome.NO_DECISION, false,
                    false, List.of("only Release Runs can contribute a Gate"));
        }
        // Episode FAIL can come from a Judge; only deterministic grader FAIL is a hard failure.
        boolean hardFailure = runs.listEpisodes(runId).stream()
                .flatMap(episode -> runs.listGraders(episode.getId()).stream())
                .anyMatch(grader -> grader.getStatus() == EvalEpisodeStatus.FAIL);
        EvalGateDecisionRecord gate = runs.findGate(runId).orElse(null);
        if (gate == null) {
            return new TargetDecision(target, runId, required, EvalGateOutcome.NO_DECISION, false,
                    hardFailure, List.of("Release Gate decision is unavailable"));
        }
        return new TargetDecision(target, runId, required, gate.getOutcome(), gate.isOverrideApproved(),
                hardFailure, reasons(gate.getReasonsJson()));
    }

    private List<String> reasons(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<String> values = JSON.parseArray(json, String.class);
        return values == null ? List.of() : List.copyOf(values);
    }

    private Decision decision(EvalGateOutcome outcome, Map<EvaluationTarget, TargetDecision> targets,
                              List<String> reasons, List<String> warnings) {
        int exitCode = outcome == EvalGateOutcome.PASS ? 0 : outcome == EvalGateOutcome.BLOCK ? 1 : 2;
        return new Decision(outcome, exitCode, Map.copyOf(targets), List.copyOf(reasons), List.copyOf(warnings));
    }

    public record TargetDecision(EvaluationTarget target, String evalRunId, boolean required,
                                 EvalGateOutcome outcome, boolean overrideApproved,
                                 boolean hardFailure, List<String> reasons) { }

    /** Exit codes are stable for CI: 0=PASS, 1=BLOCK, 2=NO_DECISION. */
    public record Decision(EvalGateOutcome outcome, int exitCode,
                           Map<EvaluationTarget, TargetDecision> targets,
                           List<String> reasons, List<String> warnings) { }
}
