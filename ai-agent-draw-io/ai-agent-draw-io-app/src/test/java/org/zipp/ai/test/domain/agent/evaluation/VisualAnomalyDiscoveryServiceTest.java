package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;
import org.zipp.ai.domain.agent.service.evaluation.intake.VisualAnomalyDiscoveryService;
import org.zipp.ai.domain.agent.service.evaluation.visual.IDiagramImageRenderer;
import org.zipp.ai.domain.agent.service.evaluation.visual.IVisualAnomalyMiner;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class VisualAnomalyDiscoveryServiceTest {
    private static final String XML = "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/><mxCell id=\"a\" value=\"A\" vertex=\"1\" parent=\"1\"><mxGeometry x=\"10\" y=\"10\" width=\"100\" height=\"50\" as=\"geometry\"/></mxCell></root></mxGraphModel>";

    @Test
    public void subjectiveReadabilityFindingCreatesOnlySanitizedCandidateEvidence() {
        EvalCaseCandidate[] inserted = new EvalCaseCandidate[1];
        AgentRunTelemetry run = AgentRunTelemetry.builder().id("run-source").userId("user-private").diagramId("diagram-private").agentId("drawing").build();
        IAgentUsageTelemetryStore telemetry = proxy(IAgentUsageTelemetryStore.class, (method, args) -> method.getName().equals("findRunDetail") ? Optional.of(AgentRunDetail.builder().run(run).steps(List.of()).build()) : defaultValue(method.getReturnType()));
        ICanvasStateStore canvases = proxy(ICanvasStateStore.class, (method, args) -> method.getName().equals("find") ? Optional.of(CanvasState.builder().currentXml(XML).diagramType("flowchart").build()) : defaultValue(method.getReturnType()));
        ITraceToEvalStore store = proxy(ITraceToEvalStore.class, (method, args) -> { if (method.getName().equals("insertCandidate")) inserted[0] = (EvalCaseCandidate) args[0]; if (method.getName().equals("findCandidateBySourceRunAndFailureFamily")) return Optional.empty(); return defaultValue(method.getReturnType()); });
        IDiagramImageRenderer renderer = xml -> new IDiagramImageRenderer.RenderedDiagram("pixels".getBytes(StandardCharsets.UTF_8), "image/png", "fixture", 10, 10);
        IVisualAnomalyMiner miner = new IVisualAnomalyMiner() {
            @Override public Finding analyze(Input input) { return new Finding(true, 0.91D, "READABILITY", List.of("TEXT_TOO_SMALL"), "high", "SYNTHETIC_SMALL_LABEL_FLOW", true); }
            @Override public String version() { return "visual-v1"; }
        };
        VisualAnomalyDiscoveryService service = new VisualAnomalyDiscoveryService(telemetry, canvases, store, renderer, miner,
                true, true, "visual-v1", 1000, Clock.systemUTC());

        VisualAnomalyDiscoveryService.Result result = service.analyzeRun("run-source", "admin-1", true);

        assertEquals("CANDIDATE_CREATED", result.status());
        assertNotNull(inserted[0]); assertEquals("visual_readability", inserted[0].getFailureFamily());
        assertTrue(inserted[0].getModelEvidence().stream().anyMatch(value -> value.startsWith("Synthetic reconstruction:")));
        assertFalse(inserted[0].getEvidenceSummary().contains("user-private")); assertFalse(inserted[0].getEvidenceSummary().contains(XML));
    }

    @Test
    public void uncalibratedMinerFailsClosedBeforeProductionCanvasAccess() {
        int[] productionReads = new int[1];
        IAgentUsageTelemetryStore telemetry = proxy(IAgentUsageTelemetryStore.class, (method, args) -> { productionReads[0]++; return defaultValue(method.getReturnType()); });
        ICanvasStateStore canvases = proxy(ICanvasStateStore.class, (method, args) -> { productionReads[0]++; return defaultValue(method.getReturnType()); });
        IVisualAnomalyMiner miner = new IVisualAnomalyMiner() { @Override public Finding analyze(Input input) { return null; } @Override public String version() { return "visual-v1"; } };
        VisualAnomalyDiscoveryService service = new VisualAnomalyDiscoveryService(telemetry, canvases, proxy(ITraceToEvalStore.class, (method, args) -> defaultValue(method.getReturnType())),
                xml -> null, miner, true, false, "visual-v1", 1000, Clock.systemUTC());
        assertEquals("UNAVAILABLE", service.analyzeRun("run-source", "admin-1", true).status());
        assertEquals(0, productionReads[0]);
    }

    @Test
    public void unconfiguredMinerModelFailsClosedBeforeProductionCanvasAccess() {
        int[] productionReads = new int[1];
        IAgentUsageTelemetryStore telemetry = proxy(IAgentUsageTelemetryStore.class, (method, args) -> { productionReads[0]++; return defaultValue(method.getReturnType()); });
        ICanvasStateStore canvases = proxy(ICanvasStateStore.class, (method, args) -> { productionReads[0]++; return defaultValue(method.getReturnType()); });
        IVisualAnomalyMiner miner = new IVisualAnomalyMiner() { @Override public Finding analyze(Input input) { return null; } @Override public String version() { return "model=unconfigured;prompt=visual-v1;schema=visual-v1"; } };
        VisualAnomalyDiscoveryService service = new VisualAnomalyDiscoveryService(telemetry, canvases, proxy(ITraceToEvalStore.class, (method, args) -> defaultValue(method.getReturnType())),
                xml -> null, miner, true, true, miner.version(), 1000, Clock.systemUTC());

        VisualAnomalyDiscoveryService.Result result = service.analyzeRun("run-source", "admin-1", true);

        assertEquals("UNAVAILABLE", result.status());
        assertEquals("visual_model_version_unconfigured", result.reason());
        assertEquals(0, productionReads[0]);
    }

    private static Object defaultValue(Class<?> type) { if (!type.isPrimitive()) return null; if (type == boolean.class) return false; if (type == int.class) return 0; if (type == long.class) return 0L; return 0D; }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Invocation invocation) { return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (value, method, args) -> invocation.call(method, args == null ? new Object[0] : args)); }
    private interface Invocation { Object call(java.lang.reflect.Method method, Object[] args); }
}
