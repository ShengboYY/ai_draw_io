package org.zipp.ai.test.domain.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.*;

import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;

public class EvalRunQueryServiceTest {
    @Test
    public void readModelSeparatesSummaryMatrixDetailAndLargeArtifact() throws Exception {
        Store store = new Store(); ArtifactStore artifacts = new ArtifactStore();
        EvalRun run = EvalRun.builder().id("run-1").mode(EvalRunMode.MODE_B).datasetId("core")
                .datasetVersion("v1").repetitions(1).plannedEpisodes(2).gitSha("sha")
                .status(EvalRunStatus.RUNNING).createdAt(Instant.now()).build();
        store.insertRun(run);
        store.insertRun(run.toBuilder().id("run-2").build());
        EvalEpisode episode = EvalEpisode.builder().id("ep-1").evalRunId("run-1").caseId("case-1")
                .caseVersion("1").status(EvalEpisodeStatus.FAIL).attempt(1).traceRef("artifact-1").build();
        store.saveEpisode(episode);
        store.replaceGraders("ep-1", List.of(EvalGraderResultRecord.builder().episodeId("ep-1")
                .graderName("graph").graderVersion("v1").status(EvalEpisodeStatus.FAIL)
                .severity("major").evidenceJson("[\"missing node\"]").build()));
        EvalCaseDefinition definition = EvalCaseDefinition.builder().caseId("case-1").caseVersion("1")
                .risk("high").diagramType("architecture").tags(List.of("lang:en", "agent:drawing"))
                .input(Map.of("user", "draw synthetic service"))
                .expected(EvalCaseDefinition.Expected.builder().routeType("create_new").build()).build();
        EvalExecution execution = EvalExecution.builder().evalCase(definition)
                .trace(EvalTrace.builder().runStatus(EvalTrace.RunStatus.SUCCESS).build())
                .initialCanvasXml("<mxGraphModel><root/></mxGraphModel>")
                .finalCanvasXml("<mxGraphModel><root/></mxGraphModel>").build();
        artifacts.values.put("artifact-1", new ObjectMapper().findAndRegisterModules().writeValueAsBytes(execution));
        EvalRunQueryService query = new EvalRunQueryService(store, (id, version, role) -> List.of(definition), artifacts);

        EvalRunSummaryView summary = query.summary("run-1");
        EvalEpisodeView row = query.episodes("run-1", "FAIL", "create_new", "high", "en", "drawing", EvalAdminRole.ADMIN).get(0);
        EvalEpisodeDetailView detail = query.detail("run-1", "ep-1", EvalAdminRole.ADMIN);
        EvalEpisodeArtifactView artifact = query.artifact("run-1", "ep-1", EvalAdminRole.ADMIN);

        assertEquals(0.5D, summary.getProgress(), 0.001D);
        assertEquals("drawing", row.getAgent());
        assertEquals("Failed graders: graph (major)", row.getBlockingReason());
        assertTrue(query.episodes("run-1", null, null, null, null, "intent-router", EvalAdminRole.ADMIN).isEmpty());
        assertEquals("draw synthetic service", detail.getInput().get("user"));
        assertNotNull(artifact.getTrace());
        assertTrue(artifact.getInitialCanvasImageDataUrl().startsWith("data:image/svg+xml;base64,"));
        assertTrue(artifact.getFinalCanvasImageDataUrl().startsWith("data:image/svg+xml;base64,"));
        assertEquals(2, artifact.getSemanticDiff().size());
        assertThrows(SecurityException.class, () -> query.detail("run-2", "ep-1", EvalAdminRole.ADMIN));
    }

    @Test
    public void sequesteredEpisodeContentRequiresReleaseOwnerRole() {
        Store store = new Store();
        store.insertRun(EvalRun.builder().id("release-1").mode(EvalRunMode.RELEASE)
                .datasetId("sealed").datasetVersion("v1").status(EvalRunStatus.COMPLETED).build());
        store.saveEpisode(EvalEpisode.builder().id("ep-sealed").evalRunId("release-1")
                .caseId("sealed-case").caseVersion("1").status(EvalEpisodeStatus.PASS).build());
        EvalCaseDefinition definition = EvalCaseDefinition.builder().caseId("sealed-case").caseVersion("1")
                .input(Map.of("user", "synthetic sealed task")).build();
        IEvalDatasetCaseSource source = (id, version, role) -> {
            if (role != EvalAdminRole.RELEASE_OWNER) throw new SecurityException("Release Owner role is required");
            return List.of(definition);
        };
        EvalRunQueryService query = new EvalRunQueryService(store, source, new ArtifactStore());

        assertThrows(SecurityException.class, () -> query.detail("release-1", "ep-sealed", EvalAdminRole.ADMIN));
        assertEquals("synthetic sealed task", query.detail("release-1", "ep-sealed", EvalAdminRole.RELEASE_OWNER)
                .getInput().get("user"));
    }

    private static final class ArtifactStore implements IEvalRunArtifactStore {
        final Map<String, byte[]> values = new HashMap<>();
        @Override public String put(String run, String episode, String type, byte[] content) { return null; }
        @Override public Optional<byte[]> read(String ref) { return Optional.ofNullable(values.get(ref)); }
    }
    private static final class Store implements IEvalRunStore {
        final Map<String, EvalRun> runs = new HashMap<>(); final Map<String, EvalEpisode> episodes = new HashMap<>(); final Map<String, List<EvalGraderResultRecord>> graders = new HashMap<>();
        @Override public void insertRun(EvalRun value) { runs.put(value.getId(), value); }
        @Override public void updateRun(EvalRun value) { runs.put(value.getId(), value); }
        @Override public Optional<EvalRun> findRun(String id) { return Optional.ofNullable(runs.get(id)); }
        @Override public Optional<EvalRun> findByIdempotencyKey(String key) { return Optional.empty(); }
        @Override public List<EvalRun> listRuns(int limit, int offset) { return runs.values().stream().toList(); }
        @Override public void saveEpisode(EvalEpisode value) { episodes.put(value.getId(), value); }
        @Override public Optional<EvalEpisode> findEpisode(String id) { return Optional.ofNullable(episodes.get(id)); }
        @Override public List<EvalEpisode> listEpisodes(String runId) { return episodes.values().stream().filter(value -> runId.equals(value.getEvalRunId())).toList(); }
        @Override public void replaceGraders(String id, List<EvalGraderResultRecord> values) { graders.put(id, values); }
        @Override public List<EvalGraderResultRecord> listGraders(String id) { return graders.getOrDefault(id, List.of()); }
    }
}
