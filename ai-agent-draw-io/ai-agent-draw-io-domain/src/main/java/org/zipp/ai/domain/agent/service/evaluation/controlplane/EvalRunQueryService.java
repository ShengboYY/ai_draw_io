package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.DrawioGraphNormalizer;

import java.util.*;

/** Read-model assembler for Run → Case → Episode → Evidence navigation. */
@Service
public class EvalRunQueryService {
    private final IEvalRunStore store;
    private final IEvalDatasetCaseSource cases;
    private final IEvalRunArtifactStore artifacts;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public EvalRunQueryService(IEvalRunStore store, IEvalDatasetCaseSource cases, IEvalRunArtifactStore artifacts) {
        this.store = store; this.cases = cases; this.artifacts = artifacts;
    }

    public List<EvalRunSummaryView> list(int limit, int offset) {
        return store.listRuns(Math.max(1, Math.min(limit, 200)), Math.max(0, offset)).stream().map(this::summary).toList();
    }

    public EvalRunSummaryView summary(String runId) { return summary(requireRun(runId)); }

    public List<EvalEpisodeView> episodes(String runId, String status, String route, String risk, String language,
                                          String agent) {
        EvalRun run = requireRun(runId);
        Map<String, EvalCaseDefinition> definitions = definitions(run);
        return store.listEpisodes(runId).stream().map(episode -> view(episode,
                        definitions.get(key(episode.getCaseId(), episode.getCaseVersion()))))
                .filter(value -> blank(status) || value.getStatus().name().equalsIgnoreCase(status))
                .filter(value -> blank(route) || Objects.equals(value.getRoute(), route))
                .filter(value -> blank(risk) || Objects.equals(value.getRisk(), risk))
                .filter(value -> blank(language) || Objects.equals(value.getLanguage(), language))
                .filter(value -> blank(agent) || Objects.equals(value.getAgent(), agent)).toList();
    }

    public EvalEpisodeDetailView detail(String runId, String episodeId) {
        EvalRun run = requireRun(runId);
        EvalEpisode episode = requireEpisode(runId, episodeId);
        EvalCaseDefinition definition = definitions(run).get(key(episode.getCaseId(), episode.getCaseVersion()));
        if (definition == null) throw new IllegalStateException("Episode Case is missing from immutable Dataset Version");
        return EvalEpisodeDetailView.builder().episode(view(episode, definition)).input(definition.getInput())
                .expected(definition.getExpected()).build();
    }

    public EvalEpisodeArtifactView artifact(String runId, String episodeId) {
        EvalRun run = requireRun(runId);
        EvalEpisode episode = requireEpisode(runId, episodeId);
        // Loading definitions enforces the sequestered Dataset boundary before any artifact read.
        Map<String, EvalCaseDefinition> definitions = definitions(run);
        EvalCaseDefinition definition = definitions.get(key(episode.getCaseId(), episode.getCaseVersion()));
        if (episode.getTraceRef() == null) throw new IllegalStateException("Episode has no execution artifact");
        byte[] bytes = artifacts.read(episode.getTraceRef())
                .orElseThrow(() -> new IllegalStateException("Episode execution artifact is unavailable"));
        try {
            EvalExecution execution = mapper.readValue(bytes, EvalExecution.class);
            return EvalEpisodeArtifactView.builder().trace(execution.getTrace())
                    .initialCanvasXml(execution.getInitialCanvasXml()).finalCanvasXml(execution.getFinalCanvasXml())
                    .semanticDiff(semanticDiff(execution, definition)).build();
        } catch (Exception e) {
            throw new IllegalStateException("Episode execution artifact is invalid", e);
        }
    }

    private EvalRunSummaryView summary(EvalRun run) {
        List<EvalEpisode> episodes = store.listEpisodes(run.getId());
        int expected = Math.max(run.getPlannedEpisodes(), episodes.size());
        int completed = episodes.size();
        return EvalRunSummaryView.builder().id(run.getId()).mode(run.getMode()).datasetId(run.getDatasetId())
                .datasetVersion(run.getDatasetVersion()).status(run.getStatus()).gitSha(run.getGitSha())
                .executionProfileHash(run.getExecutionProfileHash()).repetitions(run.getRepetitions())
                .totalEpisodes(expected).completedEpisodes(completed)
                .passCount(count(episodes, EvalEpisodeStatus.PASS)).failCount(count(episodes, EvalEpisodeStatus.FAIL))
                .errorCount(count(episodes, EvalEpisodeStatus.ERROR)).unavailableCount(count(episodes, EvalEpisodeStatus.UNAVAILABLE))
                .progress(run.getStatus() == EvalRunStatus.COMPLETED ? 1D : expected == 0 ? 0D : completed / (double) expected)
                .totalLatencyMs(episodes.stream().mapToLong(EvalEpisode::getLatencyMs).sum())
                .estimatedCost(episodes.stream().mapToDouble(EvalEpisode::getEstimatedCost).sum())
                .baselineRef(run.getBaselineRef()).candidateRef(run.getCandidateRef())
                .createdAt(run.getCreatedAt()).startedAt(run.getStartedAt()).completedAt(run.getCompletedAt()).build();
    }

    private EvalEpisodeView view(EvalEpisode episode, EvalCaseDefinition definition) {
        String route = definition == null || definition.getExpected() == null ? "unknown" : value(definition.getExpected().getRouteType());
        List<EvalGraderResultRecord> graders = store.listGraders(episode.getId());
        return EvalEpisodeView.builder().id(episode.getId()).caseId(episode.getCaseId()).caseVersion(episode.getCaseVersion())
                .repetition(episode.getRepetition()).attempt(episode.getAttempt()).status(episode.getStatus())
                .route(route).risk(definition == null ? "unknown" : value(definition.getRisk()))
                .language(tag(definition, "lang:", "unknown")).diagramType(definition == null ? "unknown" : value(definition.getDiagramType()))
                .agent(tag(definition, "agent:", inferAgent(route))).latencyMs(episode.getLatencyMs())
                .estimatedCost(episode.getEstimatedCost()).errorClass(episode.getErrorClass()).errorMessage(episode.getErrorMessage())
                .blockingReason(blockingReason(episode, graders)).graders(graders).build();
    }

    private String blockingReason(EvalEpisode episode, List<EvalGraderResultRecord> graders) {
        // Keep the primary reason readable without forcing operators to inspect raw grader JSON.
        if (episode.getStatus() == EvalEpisodeStatus.ERROR) {
            return "Infrastructure error: " + value(episode.getErrorClass()) + " — " + value(episode.getErrorMessage());
        }
        if (episode.getStatus() == EvalEpisodeStatus.UNAVAILABLE) return "Evaluation evidence is unavailable";
        if (episode.getStatus() != EvalEpisodeStatus.FAIL) return null;
        List<String> failed = graders.stream().filter(value -> value.getStatus() == EvalEpisodeStatus.FAIL)
                .map(value -> value.getGraderName() + " (" + value(value.getSeverity()) + ")").toList();
        return failed.isEmpty() ? "Agent output failed an evaluation assertion" : "Failed graders: " + String.join(", ", failed);
    }

    private List<String> semanticDiff(EvalExecution execution, EvalCaseDefinition definition) {
        Map<String, String> aliases = definition != null && definition.getExpected() != null
                && definition.getExpected().getGraph() != null ? definition.getExpected().getGraph().getAliases() : Map.of();
        DrawioGraphNormalizer normalizer = new DrawioGraphNormalizer();
        Set<String> before = normalizer.normalize(execution.getInitialCanvasXml(), aliases).nodes();
        Set<String> after = normalizer.normalize(execution.getFinalCanvasXml(), aliases).nodes();
        Set<String> added = new LinkedHashSet<>(after); added.removeAll(before);
        Set<String> removed = new LinkedHashSet<>(before); removed.removeAll(after);
        return List.of("added nodes: " + added, "removed nodes: " + removed);
    }

    private Map<String, EvalCaseDefinition> definitions(EvalRun run) {
        Map<String, EvalCaseDefinition> values = new LinkedHashMap<>();
        for (EvalCaseDefinition definition : cases.loadPublished(run.getDatasetId(), run.getDatasetVersion(), EvalAdminRole.ADMIN)) {
            values.put(key(definition.getCaseId(), definition.getCaseVersion()), definition);
        }
        return values;
    }
    private EvalRun requireRun(String id) { return store.findRun(id).orElseThrow(() -> new IllegalArgumentException("Eval Run not found")); }
    private EvalEpisode requireEpisode(String runId, String id) { EvalEpisode value = store.findEpisode(id).orElseThrow(() -> new IllegalArgumentException("Eval Episode not found")); if (!runId.equals(value.getEvalRunId())) throw new SecurityException("Episode does not belong to Eval Run"); return value; }
    private int count(List<EvalEpisode> values, EvalEpisodeStatus status) { return (int) values.stream().filter(value -> value.getStatus() == status).count(); }
    private String key(String id, String version) { return id + "@" + version; }
    private String value(String value) { return blank(value) ? "unknown" : value; }
    private String tag(EvalCaseDefinition definition, String prefix, String fallback) { if (definition == null || definition.getTags() == null) return fallback; return definition.getTags().stream().filter(value -> value != null && value.startsWith(prefix)).map(value -> value.substring(prefix.length())).findFirst().orElse(fallback); }
    private String inferAgent(String route) { return "answer_only".equals(route) || "clarify".equals(route) ? "intent-router" : "drawing-agent"; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
