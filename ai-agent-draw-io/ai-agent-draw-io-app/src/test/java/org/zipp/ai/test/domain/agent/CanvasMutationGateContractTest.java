package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasField;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationAuthorization;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationCommand;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationDecision;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationPurpose;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationRejectionReason;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationStatus;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasRepairScope;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateSaveResult;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateVersionConflictException;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.canvas.CanvasMutationGate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CanvasMutationGateContractTest {

    @Test
    public void shouldAcceptAndPersistOneCanonicalUserEdit() {
        String before = diagram("API", 120);
        String candidate = diagram("API v2", 160);
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:before")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.USER_EDIT,
                before,
                candidate,
                DiagramType.FLOWCHART,
                CanvasMutationAuthorization.unrestricted(),
                "alice",
                "diagram-1",
                3L,
                "sha256:before"));

        assertEquals(CanvasMutationStatus.ACCEPTED, decision.status());
        assertEquals(1, store.saves.size());
        assertTrue(store.saves.get(0).getCurrentXml().contains("value='API v2'"));
        assertEquals("flowchart", store.saves.get(0).getDiagramType());
        assertEquals(4L, decision.savedState().getVersion().longValue());
        assertEquals(java.util.Set.of("2"), decision.changedCellIds());
    }

    @Test
    public void shouldKeepTheStoredDiagramTypeWhenAnEditRouteIsGeneric() {
        String before = diagram("API", 120);
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .diagramType("architecture")
                .currentXml(before)
                .contentHash("sha256:before")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.USER_EDIT,
                before,
                diagram("API v2", 160),
                DiagramType.GENERIC,
                CanvasMutationAuthorization.unrestricted(),
                "alice",
                "diagram-1",
                3L,
                "sha256:before"));

        assertEquals(CanvasMutationStatus.ACCEPTED, decision.status());
        assertEquals("architecture", decision.savedState().getDiagramType());
    }

    @Test
    public void shouldCanonicalizeAUserCreateExactlyAtTheGate() {
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(null);
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.USER_CREATE,
                "",
                "<mxCell id='2' value='<heap & metaspace>' vertex='1' parent='1'>"
                        + "<mxGeometry x='100' y='100' width='180' height='70' as='geometry'/></mxCell>",
                DiagramType.FLOWCHART,
                CanvasMutationAuthorization.unrestricted(),
                "alice",
                "diagram-1",
                null,
                null));

        assertEquals(CanvasMutationStatus.ACCEPTED, decision.status());
        assertEquals(1, store.saves.size());
        assertTrue(decision.resultingXml().contains("<mxGraphModel>"));
        assertTrue(decision.resultingXml().contains("value='&lt;heap &amp; metaspace&gt;'"));
    }

    @Test
    public void shouldRejectAStaleVersionAndKeepTheStoredCanvas() {
        String before = diagram("Current", 120);
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.USER_EDIT,
                diagram("Stale baseline", 120),
                diagram("Stale edit", 160),
                DiagramType.FLOWCHART,
                CanvasMutationAuthorization.unrestricted(),
                "alice",
                "diagram-1",
                2L,
                "sha256:current"));

        assertEquals(CanvasMutationStatus.STALE_VERSION, decision.status());
        assertEquals(CanvasMutationRejectionReason.VERSION_MISMATCH, decision.rejectionReason());
        assertEquals(before, decision.resultingXml());
        assertTrue(store.saves.isEmpty());
    }

    @Test
    public void shouldRejectAStaleContentHashAndKeepTheStoredCanvas() {
        String before = diagram("Current", 120);
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.USER_EDIT,
                before,
                diagram("Stale edit", 160),
                DiagramType.FLOWCHART,
                CanvasMutationAuthorization.unrestricted(),
                "alice",
                "diagram-1",
                3L,
                "sha256:stale"));

        assertEquals(CanvasMutationStatus.STALE_VERSION, decision.status());
        assertEquals(CanvasMutationRejectionReason.CONTENT_HASH_MISMATCH, decision.rejectionReason());
        assertEquals(before, decision.resultingXml());
        assertTrue(store.saves.isEmpty());
    }

    @Test
    public void shouldRejectAnEditWithoutAContentHashBaseline() {
        String before = diagram("Current", 120);
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.USER_EDIT,
                before,
                diagram("Unversioned edit", 160),
                DiagramType.FLOWCHART,
                CanvasMutationAuthorization.unrestricted(),
                "alice",
                "diagram-1",
                3L,
                null));

        assertEquals(CanvasMutationStatus.STALE_VERSION, decision.status());
        assertEquals(CanvasMutationRejectionReason.CONTENT_HASH_MISMATCH, decision.rejectionReason());
        assertTrue(store.saves.isEmpty());
    }

    @Test
    public void shouldRejectAnInvalidCandidateAndKeepTheStoredCanvas() {
        String before = diagram("Current", 120);
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.USER_EDIT,
                before,
                "<mxGraphModel><root><mxCell",
                DiagramType.FLOWCHART,
                CanvasMutationAuthorization.unrestricted(),
                "alice",
                "diagram-1",
                3L,
                "sha256:current"));

        assertEquals(CanvasMutationStatus.REJECTED_INVALID_CANDIDATE, decision.status());
        assertEquals(CanvasMutationRejectionReason.INVALID_CANDIDATE, decision.rejectionReason());
        assertEquals(before, decision.resultingXml());
        assertTrue(store.saves.isEmpty());
    }

    @Test
    public void shouldRejectARepairThatChangesAnUnauthorizedCellOrField() {
        String before = diagram("Current", 120);
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.DETERMINISTIC_REPAIR,
                before,
                diagram("Unauthorized label", 120),
                DiagramType.FLOWCHART,
                new CanvasMutationAuthorization(
                        Set.of("3"),
                        Set.of(CanvasField.GEOMETRY),
                        CanvasRepairScope.TARGET_CELLS),
                "alice",
                "diagram-1",
                3L,
                "sha256:current"));

        assertEquals(CanvasMutationStatus.REJECTED_SCOPE_VIOLATION, decision.status());
        assertEquals(CanvasMutationRejectionReason.SCOPE_VIOLATION, decision.rejectionReason());
        assertEquals(Set.of("2"), decision.changedCellIds());
        assertTrue(store.saves.isEmpty());
    }

    @Test
    public void shouldRejectARepairThatChangesCellSemantics() {
        String before = diagram("Current", 120);
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.VLM_REPAIR,
                before,
                before.replace("vertex='1'", "vertex='0' zippRole='hidden'"),
                DiagramType.FLOWCHART,
                new CanvasMutationAuthorization(
                        Set.of("2"),
                        Set.of(CanvasField.GEOMETRY),
                        CanvasRepairScope.TARGET_CELLS),
                "alice",
                "diagram-1",
                3L,
                "sha256:current"));

        assertEquals(CanvasMutationStatus.REJECTED_SCOPE_VIOLATION, decision.status());
        assertTrue(store.saves.isEmpty());
    }

    @Test
    public void shouldRejectAStyleRepairThatChangesAnEdgeRole() {
        String before = edgeDiagram("classic");
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.VLM_REPAIR,
                before,
                edgeDiagram("ERmany"),
                DiagramType.ER,
                new CanvasMutationAuthorization(
                        Set.of("4"),
                        Set.of(CanvasField.STYLE),
                        CanvasRepairScope.TARGET_CELLS),
                "alice",
                "diagram-1",
                3L,
                "sha256:current"));

        assertEquals(CanvasMutationStatus.REJECTED_SCOPE_VIOLATION, decision.status());
        assertTrue(decision.changedFields().get("4").contains(CanvasField.SEMANTICS));
        assertTrue(store.saves.isEmpty());
    }

    @Test
    public void shouldRejectARepairWithoutAnExplicitTargetScope() {
        String before = diagram("Current", 120);
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.VLM_REPAIR,
                before,
                before,
                DiagramType.FLOWCHART,
                CanvasMutationAuthorization.unrestricted(),
                "alice",
                "diagram-1",
                3L,
                "sha256:current"));

        assertEquals(CanvasMutationStatus.REJECTED_SCOPE_VIOLATION, decision.status());
        assertEquals(CanvasMutationRejectionReason.SCOPE_VIOLATION, decision.rejectionReason());
        assertTrue(store.saves.isEmpty());
    }

    @Test
    public void shouldRejectARepairThatRegressesTheQualityVector() {
        String before = diagram("Current", 120);
        String overlappingCandidate = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='2' value='Current' vertex='1' parent='1'>"
                + "<mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='Overlap' vertex='1' parent='1'>"
                + "<mxGeometry x='110' y='110' width='120' height='60' as='geometry'/></mxCell>"
                + "</root></mxGraphModel>";
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.DETERMINISTIC_REPAIR,
                before,
                overlappingCandidate,
                DiagramType.FLOWCHART,
                new CanvasMutationAuthorization(
                        Set.of("3"),
                        Set.of(CanvasField.ADD_CELL),
                        CanvasRepairScope.TARGET_CELLS),
                "alice",
                "diagram-1",
                3L,
                "sha256:current"));

        assertEquals(CanvasMutationStatus.REJECTED_REGRESSION, decision.status());
        assertEquals(CanvasMutationRejectionReason.QUALITY_REGRESSION, decision.rejectionReason());
        assertEquals(before, decision.resultingXml());
        assertTrue(store.saves.isEmpty());
    }

    @Test
    public void shouldRejectMajorRegressionEvenWhenCriticalCountImproves() {
        String before = diagram("Before", 120);
        String candidate = diagram("After", 120);
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        DefaultCanvasAnalyzer delegate = new DefaultCanvasAnalyzer();
        CanvasMutationGate gate = new CanvasMutationGate(store, (xml, diagramType) -> {
            var analysis = delegate.analyze(xml, diagramType);
            boolean after = xml.contains("After");
            analysis.setValid(true);
            analysis.setSeverity(after ? "major" : "critical");
            analysis.setIssues(List.of(CanvasAnalysisIssue.builder()
                    .type(after ? CanvasIssueType.TEXT_OVERFLOW : CanvasIssueType.NODE_OVERLAP)
                    .severity(after ? "major" : "critical")
                    .targetCellIds(List.of("2"))
                    .build()));
            return analysis;
        });

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.DETERMINISTIC_REPAIR,
                before,
                candidate,
                DiagramType.FLOWCHART,
                new CanvasMutationAuthorization(
                        Set.of("2"),
                        Set.of(CanvasField.VALUE),
                        CanvasRepairScope.TARGET_CELLS),
                "alice",
                "diagram-1",
                3L,
                "sha256:current"));

        assertEquals(CanvasMutationStatus.REJECTED_REGRESSION, decision.status());
        assertEquals(CanvasMutationRejectionReason.QUALITY_REGRESSION, decision.rejectionReason());
        assertTrue(store.saves.isEmpty());
    }

    @Test
    public void shouldRejectARepairThatDoesNotImproveQuality() {
        String before = diagram("Current", 120);
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.DETERMINISTIC_REPAIR,
                before,
                before,
                DiagramType.FLOWCHART,
                new CanvasMutationAuthorization(
                        Set.of("2"),
                        Set.of(CanvasField.GEOMETRY),
                        CanvasRepairScope.TARGET_CELLS),
                "alice",
                "diagram-1",
                3L,
                "sha256:current"));

        assertEquals(CanvasMutationStatus.NO_SAFE_CANDIDATE, decision.status());
        assertEquals(CanvasMutationRejectionReason.NO_SAFE_CANDIDATE, decision.rejectionReason());
        assertEquals(before, decision.resultingXml());
        assertTrue(store.saves.isEmpty());
    }

    @Test
    public void shouldAcceptABoundedNonRegressiveVlmRepairForLaterPixelVerification() {
        String before = diagram("Current", 120);
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.VLM_REPAIR,
                before,
                diagram("Current", 160),
                DiagramType.FLOWCHART,
                new CanvasMutationAuthorization(
                        Set.of("2"),
                        Set.of(CanvasField.GEOMETRY),
                        CanvasRepairScope.TARGET_CELLS),
                "alice",
                "diagram-1",
                3L,
                "sha256:current"));

        assertEquals(CanvasMutationStatus.ACCEPTED, decision.status());
        assertEquals(Set.of("2"), decision.changedCellIds());
        assertEquals(1, store.saves.size());
    }

    @Test
    public void shouldAcceptARepairThatRemovesAnIssueOnItsAuthorizedCell() {
        String before = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='2' value='API' vertex='1' parent='1'>"
                + "<mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='Worker' vertex='1' parent='1'>"
                + "<mxGeometry x='110' y='110' width='120' height='60' as='geometry'/></mxCell>"
                + "</root></mxGraphModel>";
        String candidate = before.replace("x='110' y='110'", "x='320' y='100'");
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.DETERMINISTIC_REPAIR,
                before,
                candidate,
                DiagramType.FLOWCHART,
                new CanvasMutationAuthorization(
                        Set.of("3"),
                        Set.of(CanvasField.GEOMETRY),
                        CanvasRepairScope.TARGET_CELLS),
                "alice",
                "diagram-1",
                3L,
                "sha256:current"));

        assertEquals(CanvasMutationStatus.ACCEPTED, decision.status());
        assertEquals(Set.of("3"), decision.changedCellIds());
        assertEquals(1, store.saves.size());
    }

    @Test
    public void shouldReturnTheLatestCanvasWhenOptimisticSaveConflicts() {
        String before = diagram("Current", 120);
        String concurrent = diagram("Concurrent", 140);
        CapturingCanvasStateStore store = new CapturingCanvasStateStore(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(before)
                .contentHash("sha256:current")
                .version(3L)
                .build());
        store.concurrentState = CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml(concurrent)
                .contentHash("sha256:concurrent")
                .version(4L)
                .build();
        CanvasMutationGate gate = new CanvasMutationGate(store, new DefaultCanvasAnalyzer());

        CanvasMutationDecision decision = gate.evaluate(new CanvasMutationCommand(
                CanvasMutationPurpose.USER_EDIT,
                before,
                diagram("My edit", 160),
                DiagramType.FLOWCHART,
                CanvasMutationAuthorization.unrestricted(),
                "alice",
                "diagram-1",
                3L,
                "sha256:current"));

        assertEquals(CanvasMutationStatus.STALE_VERSION, decision.status());
        assertEquals(CanvasMutationRejectionReason.VERSION_MISMATCH, decision.rejectionReason());
        assertEquals(concurrent, decision.resultingXml());
        assertTrue(store.saves.isEmpty());
    }

    private String diagram(String label, int width) {
        return "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='2' value='" + label + "' vertex='1' parent='1'>"
                + "<mxGeometry x='100' y='100' width='" + width + "' height='60' as='geometry'/>"
                + "</mxCell></root></mxGraphModel>";
    }

    private String edgeDiagram(String endArrow) {
        return "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='2' value='User' vertex='1' parent='1'>"
                + "<mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='Account' vertex='1' parent='1'>"
                + "<mxGeometry x='340' y='100' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' style='edgeStyle=orthogonalEdgeStyle;endArrow=" + endArrow + ";' "
                + "edge='1' parent='1' source='2' target='3'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>"
                + "</root></mxGraphModel>";
    }

    private static final class CapturingCanvasStateStore implements ICanvasStateStore {

        private final List<CanvasState> saves = new ArrayList<>();
        private CanvasState current;
        private CanvasState concurrentState;

        private CapturingCanvasStateStore(CanvasState current) {
            this.current = current;
        }

        @Override
        public Optional<CanvasState> find(String userId, String diagramId) {
            return Optional.ofNullable(current);
        }

        @Override
        public CanvasState save(CanvasState state) {
            if (concurrentState != null) {
                current = concurrentState;
                concurrentState = null;
                throw new CanvasStateVersionConflictException(
                        state.getUserId(), state.getDiagramId(), state.getVersion());
            }
            saves.add(state);
            state.setVersion(current == null ? 1L : current.getVersion() + 1L);
            state.setContentHash("sha256:after");
            current = state;
            return state;
        }

        @Override
        public CanvasStateSaveResult saveWithResult(CanvasState state) {
            return CanvasStateSaveResult.updated(save(state));
        }
    }
}
