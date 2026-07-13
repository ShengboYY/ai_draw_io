package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.EvalStatisticsService;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds target-specific metrics without inventing evidence for missing or ineligible Episodes. */
@Service
public class EvalTargetReportService {
    private static final String INVALID_ROUTE = "<invalid>";
    private final IEvalRunStore store;
    private final IEvalDatasetCaseSource cases;
    private final IEvalRunArtifactStore artifacts;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public EvalTargetReportService(IEvalRunStore store, IEvalDatasetCaseSource cases, IEvalRunArtifactStore artifacts) {
        this.store = store; this.cases = cases; this.artifacts = artifacts;
    }

    public EvalTargetReport report(String runId, EvalAdminRole role) {
        EvalRun run = store.findRun(runId).orElseThrow(() ->
                new EvalControlPlaneException(EvalControlPlaneErrorCode.NOT_FOUND, "Eval Run not found"));
        Map<String, EvalCaseDefinition> definitions = cases.loadPublished(run.getDatasetId(), run.getDatasetVersion(), role)
                .stream().collect(Collectors.toMap(this::caseKey, value -> value, (left, right) -> left, LinkedHashMap::new));
        List<EvalEpisode> episodes = store.listEpisodes(runId);
        List<EvalEpisode> eligible = episodes.stream().filter(this::eligible).toList();
        Map<String, EvalExecution> executions = loadExecutions(eligible);
        EvalTargetReport.EvalTargetReportBuilder result = EvalTargetReport.builder().runId(runId)
                .target(run.getEvaluationTarget()).totalEpisodes(episodes.size()).eligibleEpisodes(eligible.size())
                .excludedErrors(count(episodes, EvalEpisodeStatus.ERROR))
                .excludedUnavailable(count(episodes, EvalEpisodeStatus.UNAVAILABLE))
                .latency(latency(eligible, threshold(run, "minLatencySamplesForP95", 100)));
        if (run.getEvaluationTarget() == EvaluationTarget.INTENT_ROUTER) {
            result.router(router(eligible, definitions, executions, threshold(run, "minSamplesPerClass", 20)));
        } else if (run.getEvaluationTarget() == EvaluationTarget.DRAWING_QUALITY) {
            result.drawing(drawing(run, eligible, definitions, executions));
        } else {
            result.fullAgent(fullAgent(run, episodes, eligible, definitions, executions));
        }
        return result.build();
    }

    private EvalTargetReport.RouterMetrics router(List<EvalEpisode> episodes,
                                                   Map<String, EvalCaseDefinition> definitions,
                                                   Map<String, EvalExecution> executions,
                                                   int minimumPerClass) {
        List<RouteObservation> observations = episodes.stream().filter(episode -> {
            EvalExecution execution = executions.get(episode.getId());
            EvalCaseDefinition definition = definitions.get(caseKey(episode));
            return execution != null && execution.getTrace() != null && definition != null
                    && definition.getExpected() != null && present(definition.getExpected().getRouteType());
        }).map(episode -> {
            EvalCaseDefinition definition = definitions.get(caseKey(episode));
            String expected = definition == null || definition.getExpected() == null
                    ? INVALID_ROUTE : route(definition.getExpected().getRouteType());
            EvalExecution execution = executions.get(episode.getId());
            String actual = execution.getTrace().getRouting() == null
                    ? INVALID_ROUTE : route(execution.getTrace().getRouting().getRouteType());
            return new RouteObservation(episode, expected, actual);
        }).toList();
        Map<String, List<RouteObservation>> cells = observations.stream().collect(Collectors.groupingBy(
                value -> value.expected + "\u0000" + value.actual, LinkedHashMap::new, Collectors.toList()));
        List<EvalTargetReport.ConfusionCell> matrix = cells.values().stream().map(values ->
                EvalTargetReport.ConfusionCell.builder().expectedRoute(values.get(0).expected)
                        .actualRoute(values.get(0).actual).count(values.size())
                        .episodeIds(values.stream().map(value -> value.episode.getId()).toList()).build()).toList();
        Set<String> labels = observations.stream().map(value -> value.expected)
                .filter(value -> !INVALID_ROUTE.equals(value)).collect(Collectors.toCollection(TreeSet::new));
        List<EvalTargetReport.RouteMetric> perRoute = labels.stream().map(label -> routeMetric(label, observations)).toList();
        boolean sufficient = !labels.isEmpty() && labels.stream().allMatch(label ->
                observations.stream().filter(value -> label.equals(value.expected)).count() >= minimumPerClass);
        Map<String, Long> expectedRepetitions = episodes.stream().collect(Collectors.groupingBy(EvalEpisode::getCaseId, Collectors.counting()));
        List<List<RouteObservation>> repeated = observations.stream().collect(Collectors.groupingBy(value ->
                value.episode.getCaseId())).values().stream().filter(values -> values.size() > 1
                && values.size() == expectedRepetitions.getOrDefault(values.get(0).episode.getCaseId(), 0L)).toList();
        Double stability = repeated.isEmpty() ? null : repeated.stream()
                .filter(values -> values.stream().map(value -> value.actual).distinct().count() == 1).count() / (double) repeated.size();
        int correct = (int) observations.stream().filter(value -> value.expected.equals(value.actual)
                && !INVALID_ROUTE.equals(value.expected)).count();
        return EvalTargetReport.RouterMetrics.builder()
                .accuracy(observations.isEmpty() ? null : correct / (double) observations.size())
                .classifiedCount(observations.size()).invalidCount((int) observations.stream()
                        .filter(value -> INVALID_ROUTE.equals(value.actual)).count())
                .evidenceUnavailableCount(episodes.size() - observations.size())
                .macroF1(sufficient ? perRoute.stream().mapToDouble(value -> value.getF1() == null ? 0D : value.getF1()).average().orElse(0D) : null)
                .macroF1Availability(sufficient ? EvalTargetReport.Availability.AVAILABLE :
                        observations.isEmpty() ? EvalTargetReport.Availability.UNAVAILABLE : EvalTargetReport.Availability.COUNT_ONLY)
                .macroF1Reason(sufficient ? null : observations.isEmpty()
                        ? "No complete Router execution evidence is available"
                        : "Each expected route needs at least " + minimumPerClass + " eligible samples")
                .repeatStability(stability)
                .stabilityAvailability(stability == null ? EvalTargetReport.Availability.UNAVAILABLE : EvalTargetReport.Availability.AVAILABLE)
                .stabilityReason(stability == null ? "No Case has multiple complete eligible repetitions" : null)
                .confusionMatrix(matrix).perRoute(perRoute).build();
    }

    private EvalTargetReport.RouteMetric routeMetric(String label, List<RouteObservation> values) {
        int support = (int) values.stream().filter(value -> label.equals(value.expected)).count();
        int predicted = (int) values.stream().filter(value -> label.equals(value.actual)).count();
        int truePositive = (int) values.stream().filter(value -> label.equals(value.expected) && label.equals(value.actual)).count();
        double precision = predicted == 0 ? 0D : truePositive / (double) predicted;
        double recall = support == 0 ? 0D : truePositive / (double) support;
        double f1 = precision + recall == 0D ? 0D : 2D * precision * recall / (precision + recall);
        return EvalTargetReport.RouteMetric.builder().route(label).support(support)
                .precision(precision).recall(recall).f1(f1).build();
    }

    private EvalTargetReport.DrawingMetrics drawing(EvalRun run, List<EvalEpisode> episodes,
                                                     Map<String, EvalCaseDefinition> definitions,
                                                     Map<String, EvalExecution> executions) {
        Map<String, List<GraderObservation>> byGrader = new LinkedHashMap<>();
        Map<String, List<GraderObservation>> bySeverity = new LinkedHashMap<>();
        for (EvalEpisode episode : episodes) for (EvalGraderResultRecord grader : store.listGraders(episode.getId())) {
            GraderObservation value = new GraderObservation(episode, grader);
            byGrader.computeIfAbsent(grader.getGraderName(), ignored -> new ArrayList<>()).add(value);
            if (grader.getStatus() == EvalEpisodeStatus.FAIL && grader.getSeverity() != null
                    && !grader.getSeverity().isBlank() && !"none".equalsIgnoreCase(grader.getSeverity()))
                bySeverity.computeIfAbsent(grader.getSeverity().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(value);
        }
        Set<String> graderNames = new LinkedHashSet<>(configuredGraders(run));
        graderNames.addAll(byGrader.keySet());
        List<EvalTargetReport.GraderLayer> layers = graderNames.stream().map(graderName -> {
            List<GraderObservation> values = byGrader.getOrDefault(graderName, List.of());
            List<EvalEpisode> applicable = episodes.stream().filter(episode -> graderApplicable(graderName,
                    definitions.get(caseKey(episode)))).toList();
            Set<String> applicableIds = applicable.stream().map(EvalEpisode::getId).collect(Collectors.toSet());
            values = values.stream().filter(value -> applicableIds.contains(value.episode.getId())).toList();
            int eligible = (int) values.stream().filter(value -> value.grader.getStatus() == EvalEpisodeStatus.PASS
                    || value.grader.getStatus() == EvalEpisodeStatus.FAIL).count();
            int passed = (int) values.stream().filter(value -> value.grader.getStatus() == EvalEpisodeStatus.PASS).count();
            int unavailable = Math.max(0, applicable.size() - eligible);
            Set<String> evidenceEligibleIds = values.stream().filter(value -> value.grader.getStatus() == EvalEpisodeStatus.PASS
                    || value.grader.getStatus() == EvalEpisodeStatus.FAIL).map(value -> value.episode.getId()).collect(Collectors.toSet());
            return EvalTargetReport.GraderLayer.builder().graderName(graderName).eligibleCount(eligible)
                    .passedCount(passed).failedCount((int) values.stream().filter(value -> value.grader.getStatus() == EvalEpisodeStatus.FAIL).count())
                    .unavailableCount(unavailable).notRequiredCount(episodes.size() - applicable.size())
                    .passRate(eligible == 0 ? null : passed / (double) eligible)
                    .availability(eligible == 0 ? EvalTargetReport.Availability.UNAVAILABLE : EvalTargetReport.Availability.AVAILABLE)
                    .failedEpisodeIds(values.stream().filter(value -> value.grader.getStatus() == EvalEpisodeStatus.FAIL)
                            .map(value -> value.episode.getId()).distinct().toList())
                    .unavailableEpisodeIds(applicable.stream().map(EvalEpisode::getId)
                            .filter(value -> !evidenceEligibleIds.contains(value)).toList()).build();
        }).toList();
        List<EvalTargetReport.SeverityCount> severity = bySeverity.entrySet().stream().map(entry ->
                EvalTargetReport.SeverityCount.builder().severity(entry.getKey()).count(entry.getValue().size())
                        .episodeIds(entry.getValue().stream().map(value -> value.episode.getId()).distinct().toList()).build()).toList();
        List<EvalTargetReport.DrawingEvidence> evidence = episodes.stream().map(episode -> {
            EvalExecution execution = executions.get(episode.getId());
            return EvalTargetReport.DrawingEvidence.builder().episodeId(episode.getId()).caseId(episode.getCaseId())
                    .artifactRef(episode.getTraceRef()).beforeAvailable(execution != null && present(execution.getInitialCanvasXml()))
                    .afterAvailable(execution != null && present(execution.getFinalCanvasXml()))
                    .canvasChanged(canvasChanged(execution)).build();
        }).toList();
        List<EvalEpisode> judgeRequired = episodes.stream().filter(value -> {
            EvalCaseDefinition definition = definitions.get(caseKey(value));
            return definition != null && definition.getExpected() != null
                    && Boolean.TRUE.equals(definition.getExpected().getJudgeRequired());
        }).toList();
        int judgeAvailable = (int) judgeRequired.stream().filter(value -> store.findJudge(value.getId())
                .map(judge -> judge.getStatus() == EvalEpisodeStatus.PASS || judge.getStatus() == EvalEpisodeStatus.FAIL).orElse(false)).count();
        return EvalTargetReport.DrawingMetrics.builder().layers(layers).issueSeverities(severity).evidence(evidence)
                .judgeAvailableCount(judgeAvailable).judgeUnavailableCount(judgeRequired.size() - judgeAvailable)
                .judgeNotRequiredCount(episodes.size() - judgeRequired.size()).build();
    }

    private EvalTargetReport.FullAgentMetrics fullAgent(EvalRun run, List<EvalEpisode> all, List<EvalEpisode> eligible,
                                                         Map<String, EvalCaseDefinition> definitions,
                                                         Map<String, EvalExecution> executions) {
        List<EvalSampleResult> samples = all.stream().map(this::sample).toList();
        int minimumCases = threshold(run, "minimumCases", 1);
        double maximumErrorRate = numberThreshold(run, "maximumErrorRate", 1D);
        EvalStatisticalReport statistics = new EvalStatisticsService().summarize(samples, minimumCases, maximumErrorRate);
        List<EvalTargetReport.FunnelStage> funnel = new ArrayList<>();
        List<EvalEpisode> survivors = funnelStage(funnel, "route", eligible,
                episode -> routePassed(episode, definitions, executions));
        survivors = funnelStage(funnel, "tool", survivors,
                episode -> toolPassed(episode, definitions, executions));
        survivors = funnelStage(funnel, "mutation", survivors,
                episode -> mutationPassed(episode, definitions, executions));
        survivors = funnelStage(funnel, "structure", survivors,
                episode -> structurePassed(episode, definitions));
        funnelStage(funnel, "semantics_experience", survivors,
                episode -> semanticsPassed(episode, definitions, executions));
        boolean available = statistics.getDecision() == EvalStatisticalReport.Decision.READY;
        EvalTargetReport.Availability availability = eligible.isEmpty()
                ? EvalTargetReport.Availability.UNAVAILABLE
                : available ? EvalTargetReport.Availability.AVAILABLE : EvalTargetReport.Availability.COUNT_ONLY;
        String reason = eligible.isEmpty()
                ? "No PASS/FAIL Episodes are eligible for quality metrics"
                : available ? null : "TSR@1 requires at least " + minimumCases
                + " eligible Cases and error rate at most " + maximumErrorRate;
        return EvalTargetReport.FullAgentMetrics.builder()
                .availability(availability).unavailableReason(reason)
                .tsrAtOne(available ? statistics.getTsrAtOne() : null)
                .ciLower(available ? statistics.getCiLower() : null).ciUpper(available ? statistics.getCiUpper() : null)
                .errorRate(all.isEmpty() ? null : statistics.getErrorRate())
                .estimatedCost(statistics.getEstimatedCost()).funnel(funnel).build();
    }

    private List<EvalEpisode> funnelStage(List<EvalTargetReport.FunnelStage> stages, String stage,
                                          List<EvalEpisode> episodes, Function<EvalEpisode, StageOutcome> evaluator) {
        Map<EvalEpisode, StageOutcome> outcomes = episodes.stream().collect(Collectors.toMap(
                value -> value, evaluator, (left, right) -> left, LinkedHashMap::new));
        List<EvalEpisode> passed = outcomes.entrySet().stream().filter(value -> value.getValue() == StageOutcome.PASS)
                .map(Map.Entry::getKey).toList();
        List<EvalEpisode> failures = outcomes.entrySet().stream().filter(value -> value.getValue() == StageOutcome.FAIL)
                .map(Map.Entry::getKey).toList();
        List<EvalEpisode> unavailable = outcomes.entrySet().stream().filter(value -> value.getValue() == StageOutcome.UNAVAILABLE)
                .map(Map.Entry::getKey).toList();
        int eligible = passed.size() + failures.size();
        stages.add(EvalTargetReport.FunnelStage.builder().stage(stage).inputCount(episodes.size()).eligibleCount(eligible)
                .passedCount(passed.size()).unavailableCount(unavailable.size())
                .passRate(eligible == 0 ? null : passed.size() / (double) eligible)
                .availability(eligible == 0 ? EvalTargetReport.Availability.UNAVAILABLE : EvalTargetReport.Availability.AVAILABLE)
                .failedEpisodeIds(failures.stream().map(EvalEpisode::getId).toList())
                .unavailableEpisodeIds(unavailable.stream().map(EvalEpisode::getId).toList()).build());
        return passed;
    }

    private StageOutcome routePassed(EvalEpisode episode, Map<String, EvalCaseDefinition> definitions, Map<String, EvalExecution> executions) {
        EvalCaseDefinition definition = definitions.get(caseKey(episode)); EvalExecution execution = executions.get(episode.getId());
        if (definition == null || definition.getExpected() == null
                || !present(definition.getExpected().getRouteType())
                || execution == null || execution.getTrace() == null) return StageOutcome.UNAVAILABLE;
        if (execution.getTrace().getRouting() == null) return StageOutcome.FAIL;
        return Objects.equals(route(definition.getExpected().getRouteType()), route(execution.getTrace().getRouting().getRouteType()))
                ? StageOutcome.PASS : StageOutcome.FAIL;
    }

    private StageOutcome toolPassed(EvalEpisode episode, Map<String, EvalCaseDefinition> definitions, Map<String, EvalExecution> executions) {
        EvalExecution execution = executions.get(episode.getId()); EvalCaseDefinition definition = definitions.get(caseKey(episode));
        if (execution == null || execution.getTrace() == null || definition == null || definition.getExpected() == null) return StageOutcome.UNAVAILABLE;
        List<EvalTrace.ToolCall> calls = Optional.ofNullable(execution.getTrace().getToolCalls()).orElse(List.of());
        if (calls.stream().anyMatch(value -> value.getStatus() != EvalTrace.RunStatus.SUCCESS)) return StageOutcome.FAIL;
        Set<String> allowedMutation = new HashSet<>(Optional.ofNullable(definition.getExpected().getAllowedMutationTools()).orElse(List.of()));
        int firstMutation = -1;
        for (int index = 0; index < calls.size(); index++) {
            EvalTrace.ToolCall call = calls.get(index);
            if (mutationTool(call.getName())) {
                if (!allowedMutation.contains(call.getName())) return StageOutcome.FAIL;
                if (firstMutation < 0) firstMutation = index;
            }
        }
        for (String required : Optional.ofNullable(definition.getExpected().getRequiredToolNamesBeforeMutation()).orElse(List.of())) {
            boolean beforeMutation = false;
            for (int index = 0; index < (firstMutation < 0 ? calls.size() : firstMutation); index++) {
                if (Objects.equals(required, calls.get(index).getName())) { beforeMutation = true; break; }
            }
            if (!beforeMutation && firstMutation >= 0) return StageOutcome.FAIL;
        }
        return StageOutcome.PASS;
    }

    private StageOutcome mutationPassed(EvalEpisode episode, Map<String, EvalCaseDefinition> definitions, Map<String, EvalExecution> executions) {
        EvalCaseDefinition definition = definitions.get(caseKey(episode)); EvalExecution execution = executions.get(episode.getId());
        if (definition == null || definition.getExpected() == null || execution == null || execution.getTrace() == null) return StageOutcome.UNAVAILABLE;
        Boolean required = definition.getExpected().getRequireCanvasChange();
        if (!Boolean.TRUE.equals(required)) return StageOutcome.PASS;
        List<EvalTrace.ToolCall> calls = Optional.ofNullable(execution.getTrace().getToolCalls()).orElse(List.of());
        if (calls.stream().noneMatch(value -> mutationTool(value.getName()))) return StageOutcome.FAIL;
        if (!present(execution.getTrace().getBeforeCanvasHash()) || !present(execution.getTrace().getAfterCanvasHash())) return StageOutcome.UNAVAILABLE;
        boolean changed = !Objects.equals(execution.getTrace().getBeforeCanvasHash(), execution.getTrace().getAfterCanvasHash());
        return changed ? StageOutcome.PASS : StageOutcome.FAIL;
    }

    private StageOutcome structurePassed(EvalEpisode episode, Map<String, EvalCaseDefinition> definitions) {
        EvalCaseDefinition definition = definitions.get(caseKey(episode));
        if (definition == null || definition.getExpected() == null) return StageOutcome.UNAVAILABLE;
        Set<String> expected = new LinkedHashSet<>(List.of("xml_integrity"));
        if (definition.getExpected().getGraph() != null) expected.add("graph_assertion");
        if (definition.getExpected().getProtectedNodes() != null && !definition.getExpected().getProtectedNodes().isEmpty())
            expected.add("preservation");
        Map<String, EvalGraderResultRecord> actual = store.listGraders(episode.getId()).stream()
                .filter(value -> expected.contains(value.getGraderName())).collect(Collectors.toMap(
                        EvalGraderResultRecord::getGraderName, value -> value, (left, right) -> left));
        if (!actual.keySet().containsAll(expected) || actual.values().stream()
                .anyMatch(value -> value.getStatus() == EvalEpisodeStatus.UNAVAILABLE)) return StageOutcome.UNAVAILABLE;
        return actual.values().stream().allMatch(value -> value.getStatus() == EvalEpisodeStatus.PASS)
                ? StageOutcome.PASS : StageOutcome.FAIL;
    }

    private StageOutcome semanticsPassed(EvalEpisode episode, Map<String, EvalCaseDefinition> definitions, Map<String, EvalExecution> executions) {
        EvalCaseDefinition definition = definitions.get(caseKey(episode)); EvalExecution execution = executions.get(episode.getId());
        if (definition == null || definition.getExpected() == null || execution == null || execution.getTrace() == null) return StageOutcome.UNAVAILABLE;
        boolean outcome = definition.getExpected().getTaskOutcome() == null
                || definition.getExpected().getTaskOutcome() == execution.getTrace().getTaskOutcome();
        if (!outcome) return StageOutcome.FAIL;
        boolean visualRequired = diagramRoute(definition.getExpected().getRouteType());
        Optional<EvalGraderResultRecord> visual = store.listGraders(episode.getId()).stream()
                .filter(value -> "visual_quality".equals(value.getGraderName())).findFirst();
        if (visualRequired && (visual.isEmpty() || visual.get().getStatus() == EvalEpisodeStatus.UNAVAILABLE)) return StageOutcome.UNAVAILABLE;
        if (visual.isPresent() && visual.get().getStatus() == EvalEpisodeStatus.FAIL) return StageOutcome.FAIL;
        if (Boolean.TRUE.equals(definition.getExpected().getJudgeRequired())) {
            Optional<EvalJudgeResultRecord> judge = store.findJudge(episode.getId());
            if (judge.isEmpty() || judge.get().getStatus() == EvalEpisodeStatus.UNAVAILABLE) return StageOutcome.UNAVAILABLE;
            if (judge.get().getStatus() == EvalEpisodeStatus.FAIL) return StageOutcome.FAIL;
        }
        return StageOutcome.PASS;
    }

    private EvalTargetReport.LatencySummary latency(List<EvalEpisode> episodes, int p95Threshold) {
        List<Long> values = episodes.stream().map(EvalEpisode::getLatencyMs).sorted().toList();
        boolean enough = values.size() >= p95Threshold;
        return EvalTargetReport.LatencySummary.builder().sampleCount(values.size())
                .medianMs(median(values))
                .maxMs(values.isEmpty() ? null : values.get(values.size() - 1))
                .p95Ms(enough ? values.get(Math.max(0, (int) Math.ceil(values.size() * 0.95D) - 1)) : null)
                .p95Availability(enough ? EvalTargetReport.Availability.AVAILABLE :
                        values.isEmpty() ? EvalTargetReport.Availability.UNAVAILABLE : EvalTargetReport.Availability.COUNT_ONLY)
                .p95Reason(enough ? null : "p95 requires at least " + p95Threshold + " eligible latency samples").build();
    }

    private Map<String, EvalExecution> loadExecutions(List<EvalEpisode> episodes) {
        Map<String, EvalExecution> result = new LinkedHashMap<>();
        for (EvalEpisode episode : episodes) {
            if (!present(episode.getTraceRef())) continue;
            artifacts.read(episode.getTraceRef()).ifPresent(bytes -> {
                try { result.put(episode.getId(), mapper.readValue(bytes, EvalExecution.class)); }
                catch (Exception ignored) { /* Invalid evidence remains explicitly unavailable to report projections. */ }
            });
        }
        return result;
    }

    private int threshold(EvalRun run, String field, int fallback) {
        try {
            JsonNode root = mapper.readTree(run.getProfileSnapshotJson());
            JsonNode value = root.path("config").path("gatePolicy").path(field);
            return value.isIntegralNumber() && value.asInt() > 0 ? value.asInt() : fallback;
        } catch (Exception ignored) { return fallback; }
    }

    private double numberThreshold(EvalRun run, String field, double fallback) {
        try {
            JsonNode root = mapper.readTree(run.getProfileSnapshotJson());
            JsonNode value = root.path("config").path("gatePolicy").path(field);
            return value.isNumber() && value.asDouble() >= 0D ? value.asDouble() : fallback;
        } catch (Exception ignored) { return fallback; }
    }

    private Long median(List<Long> sorted) {
        if (sorted.isEmpty()) return null;
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) :
                Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2D);
    }

    private Boolean canvasChanged(EvalExecution execution) {
        if (execution == null || execution.getTrace() == null
                || !present(execution.getTrace().getBeforeCanvasHash())
                || !present(execution.getTrace().getAfterCanvasHash())) return null;
        return !Objects.equals(execution.getTrace().getBeforeCanvasHash(), execution.getTrace().getAfterCanvasHash());
    }

    private EvalSampleResult sample(EvalEpisode episode) {
        return EvalSampleResult.builder().caseId(episode.getCaseId()).repetition(episode.getRepetition())
                .status(EvalHarnessResult.Status.valueOf(episode.getStatus().name()))
                .passed(episode.getStatus() == EvalEpisodeStatus.PASS).latencyMs(episode.getLatencyMs())
                .inputTokens(episode.getInputTokens()).outputTokens(episode.getOutputTokens())
                .estimatedCost(episode.getEstimatedCost()).build();
    }

    private String caseKey(EvalCaseDefinition value) { return value.getCaseId() + "@" + value.getCaseVersion(); }
    private String caseKey(EvalEpisode value) { return value.getCaseId() + "@" + value.getCaseVersion(); }
    private boolean eligible(EvalEpisode value) { return value.getStatus() == EvalEpisodeStatus.PASS || value.getStatus() == EvalEpisodeStatus.FAIL; }
    private int count(List<EvalEpisode> values, EvalEpisodeStatus status) { return (int) values.stream().filter(value -> value.getStatus() == status).count(); }
    private String route(String value) { return value == null || value.isBlank() ? INVALID_ROUTE : value; }
    private boolean present(String value) { return value != null && !value.isBlank(); }
    private boolean mutationTool(String name) { return Set.of("create_diagram", "modify_diagram", "apply_patch", "replace_cells").contains(name); }
    private boolean diagramRoute(String route) { return !"answer_only".equals(route) && !"clarify".equals(route); }

    private Set<String> configuredGraders(EvalRun run) {
        Set<String> result = new LinkedHashSet<>();
        try {
            JsonNode values = mapper.readTree(run.getGraderManifestJson());
            if (values.isArray()) values.forEach(value -> {
                String name = value.asText();
                if (name.startsWith("route-tool")) result.add("route_tool_policy");
                else if (name.startsWith("xml-integrity")) result.add("xml_integrity");
                else if (name.startsWith("graph-assertion")) result.add("graph_assertion");
                else if (name.startsWith("semantic-preservation")) result.add("preservation");
                else if (name.startsWith("visual-quality")) result.add("visual_quality");
            });
        } catch (Exception ignored) { /* Legacy Runs fall back to observed grader names. */ }
        return result;
    }

    private boolean graderApplicable(String grader, EvalCaseDefinition definition) {
        if (definition == null || definition.getExpected() == null) return false;
        return switch (grader) {
            case "xml_integrity", "route_tool_policy" -> true;
            case "visual_quality" -> diagramRoute(definition.getExpected().getRouteType());
            case "graph_assertion" -> definition.getExpected().getGraph() != null;
            case "preservation" -> definition.getExpected().getProtectedNodes() != null
                    && !definition.getExpected().getProtectedNodes().isEmpty();
            default -> true;
        };
    }

    private record RouteObservation(EvalEpisode episode, String expected, String actual) { }
    private record GraderObservation(EvalEpisode episode, EvalGraderResultRecord grader) { }
    private enum StageOutcome { PASS, FAIL, UNAVAILABLE }
}
