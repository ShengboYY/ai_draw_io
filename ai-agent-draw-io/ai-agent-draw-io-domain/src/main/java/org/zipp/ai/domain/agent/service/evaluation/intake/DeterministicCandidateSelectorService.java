package org.zipp.ai.domain.agent.service.evaluation.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentDiagramTraceSnapshot;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Selects review candidates from metadata-only telemetry; it never accesses debug trace content. */
@Service
public class DeterministicCandidateSelectorService {
    private static final String POLICY_VERSION = "candidate-selector-v1";
    private static final int DEFAULT_REPAIR_RETRY_BUDGET = 2;
    private static final Set<String> MUTATION_TOOLS = Set.of("create_diagram", "modify_diagram", "optimize_diagram");
    private static final Set<String> MUTATING_ROUTES = Set.of("create_new", "edit_existing", "optimize_layout");

    private final IAgentUsageTelemetryStore telemetryStore;
    private final ITraceToEvalStore intakeStore;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public DeterministicCandidateSelectorService(IAgentUsageTelemetryStore telemetryStore, ITraceToEvalStore intakeStore) {
        this(telemetryStore, intakeStore, Clock.systemUTC());
    }

    DeterministicCandidateSelectorService(IAgentUsageTelemetryStore telemetryStore, ITraceToEvalStore intakeStore, Clock clock) {
        this.telemetryStore = telemetryStore;
        this.intakeStore = intakeStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<EvalCaseCandidate> discover(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        List<EvalCaseCandidate> selected = new ArrayList<>();
        for (AgentRunTelemetry run : telemetryStore.listRuns(null, null, null, limit, 0)) {
            if (run == null || StringUtils.isBlank(run.getId())) continue;
            // Evaluate only stable terminal telemetry so a partial signal cannot hide later evidence.
            if ("RUNNING".equalsIgnoreCase(run.getStatus())) continue;
            telemetryStore.findRunDetail(run.getId()).ifPresent(detail ->
                    selected.addAll(persist(run, groupedSignals(detail, telemetryStore.listDiagramSnapshots(run.getId())))));
        }
        return selected;
    }

    private List<EvalCaseCandidate> persist(AgentRunTelemetry run, Map<String, List<Signal>> grouped) {
        List<EvalCaseCandidate> result = new ArrayList<>();
        for (Map.Entry<String, List<Signal>> entry : grouped.entrySet()) {
            String family = entry.getKey();
            EvalCaseCandidate existing = intakeStore.findCandidateBySourceRunAndFailureFamily(run.getId(), family).orElse(null);
            if (existing != null) {
                result.add(existing);
                continue;
            }
            List<Signal> signals = entry.getValue();
            Signal primary = signals.get(0);
            EvalCaseCandidate candidate = EvalCaseCandidate.builder()
                    .id("ecc_" + UUID.randomUUID())
                    .sourceRunId(run.getId())
                    .sourceSpanId(primary.spanId())
                    .sourcePhase(primary.phase())
                    .sourceAgentId(run.getAgentId())
                    .failureFamily(family)
                    .ruleId(joinDistinct(signals, Signal::ruleId, 64))
                    .evidenceSummary(joinDistinct(signals, Signal::evidence, 1024))
                    .risk(highestRisk(signals))
                    .discoveredAt(clock.instant())
                    .policyVersion(POLICY_VERSION)
                    .status(EvalCandidateStatus.DETECTED)
                    .createdBy("deterministic-selector")
                    .detectionSource("RULE_DETECTED")
                    .build();
            intakeStore.insertCandidate(candidate);
            result.add(candidate);
        }
        return result;
    }

    private Map<String, List<Signal>> groupedSignals(AgentRunDetail detail, List<AgentDiagramTraceSnapshot> snapshots) {
        Map<String, List<Signal>> grouped = new LinkedHashMap<>();
        AgentRunTelemetry run = detail.getRun();
        if (failed(run == null ? null : run.getStatus())) {
            add(grouped, new Signal("execution", "run_failed", "Run status=" + run.getStatus(), "high", run.getId(), "run"));
        }
        detail.getSteps().stream().filter(step -> failed(step.getStatus())).forEach(step ->
                add(grouped, new Signal("execution", "step_failed", "Step " + step.getPhase() + " status=" + step.getStatus(),
                        "high", step.getId(), step.getPhase())));
        detail.getLlmCalls().stream().filter(call -> failed(call.getStatus())).forEach(call ->
                add(grouped, new Signal("execution", "llm_failed", "LLM phase " + call.getPhase() + " status=" + call.getStatus(),
                        "high", call.getId(), call.getPhase())));
        detail.getToolCalls().stream().filter(call -> failed(call.getStatus())).forEach(call -> {
            add(grouped, new Signal("execution", "tool_failed", "Tool " + call.getToolName() + " status=" + call.getStatus(),
                    "high", call.getId(), call.getPhase()));
            if (MUTATION_TOOLS.contains(call.getToolName())) {
                add(grouped, new Signal("artifact", "mutation_failed", "Canvas mutation " + call.getToolName()
                        + " failed with " + StringUtils.defaultString(call.getErrorClass(), "unknown error"),
                        "high", call.getId(), call.getPhase()));
            }
        });
        addRepairSignals(grouped, detail.getTraceEvents());
        addCanvasHashSignal(grouped, detail.getTraceEvents(), snapshots);
        return grouped;
    }

    private void addRepairSignals(Map<String, List<Signal>> grouped, List<AgentTraceEvent> events) {
        for (AgentTraceEvent event : events) {
            if (!"DRAWING_MUTATION".equals(event.getEventType())) continue;
            JsonNode metadata = json(event.getMetadataJson());
            int retryCount = metadata.path("retryCount").asInt(0);
            String outcome = metadata.path("outcome").asText("");
            if (retryCount >= DEFAULT_REPAIR_RETRY_BUDGET) {
                add(grouped, new Signal("layout", "repair_budget_exceeded", "Repair retryCount=" + retryCount,
                        "medium", event.getId(), event.getPhase()));
            }
            if ("NEEDS_REPAIR".equals(outcome)) {
                add(grouped, new Signal("layout", "blocking_quality_residual", "Mutation finished with outcome=NEEDS_REPAIR",
                        "high", event.getId(), event.getPhase()));
            }
        }
    }

    private void addCanvasHashSignal(Map<String, List<Signal>> grouped, List<AgentTraceEvent> events,
                                     List<AgentDiagramTraceSnapshot> snapshots) {
        String route = events.stream().filter(event -> "ROUTING_DECIDED".equals(event.getEventType()))
                .map(event -> json(event.getMetadataJson()).path("routeType").asText(null))
                .filter(StringUtils::isNotBlank).findFirst().orElse(null);
        if (!MUTATING_ROUTES.contains(route) || snapshots == null || snapshots.size() < 2) return;
        List<AgentDiagramTraceSnapshot> ordered = snapshots.stream()
                .sorted(Comparator.comparing(AgentDiagramTraceSnapshot::getVersion,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        String before = ordered.get(0).getCanvasHash();
        String after = ordered.get(ordered.size() - 1).getCanvasHash();
        if (StringUtils.isNotBlank(before) && before.equals(after)) {
            add(grouped, new Signal("artifact", "mutating_route_canvas_unchanged",
                    "Route " + route + " kept canvas hash=" + before, "high",
                    ordered.get(ordered.size() - 1).getSpanId(), "drawing"));
        }
    }

    private JsonNode json(String value) {
        try {
            return StringUtils.isBlank(value) ? mapper.createObjectNode() : mapper.readTree(value);
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    private boolean failed(String status) {
        return "FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status);
    }

    private void add(Map<String, List<Signal>> grouped, Signal signal) {
        grouped.computeIfAbsent(signal.family(), ignored -> new ArrayList<>()).add(signal);
    }

    private String highestRisk(List<Signal> signals) {
        return signals.stream().map(Signal::risk).min(Comparator.comparingInt(this::riskRank)).orElse("medium");
    }

    private int riskRank(String risk) {
        return switch (risk) { case "critical" -> 0; case "high" -> 1; case "medium" -> 2; default -> 3; };
    }

    private String joinDistinct(List<Signal> signals, java.util.function.Function<Signal, String> value, int max) {
        String joined = String.join("; ", signals.stream().map(value).filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        return StringUtils.left(joined, max);
    }

    private record Signal(String family, String ruleId, String evidence, String risk, String spanId, String phase) { }
}
