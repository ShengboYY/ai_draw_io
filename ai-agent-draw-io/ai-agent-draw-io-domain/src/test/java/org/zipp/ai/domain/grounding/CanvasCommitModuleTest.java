package org.zipp.ai.domain.grounding;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;
import org.zipp.ai.domain.agent.model.valobj.canvas.*;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;
import org.zipp.ai.domain.agent.service.canvas.CanvasMutationGate;
import org.zipp.ai.domain.citation.model.valobj.*;
import org.zipp.ai.domain.citation.service.CitationGuard;
import org.zipp.ai.domain.grounding.port.GroundedCanvasCommitPort;
import org.zipp.ai.domain.retrieval.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CanvasCommitModuleTest {
    private static final String BEFORE = """
            <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
            <mxCell id="existing" value="Existing fact" zippCitationSchema="1"
              zippProvenanceRef="prv_trusted" zippSupportType="EVIDENCE" vertex="1" parent="1">
              <mxGeometry x="0" y="0" width="180" height="60" as="geometry"/></mxCell>
            </root></mxGraphModel>
            """;
    private static final String AFTER = """
            <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
            <mxCell id="existing" value="Existing fact" vertex="1" parent="1">
              <mxGeometry x="0" y="0" width="180" height="60" as="geometry"/></mxCell>
            <mxCell id="node-1" value="Product Owner is accountable for maximizing value"
              zippProvenanceRef="model-forged" vertex="1" parent="1"><mxGeometry x="260" y="0" width="220" height="60" as="geometry"/></mxCell>
            </root></mxGraphModel>
            """;

    @Test
    void commitsGuardedCanvasAndOnlyActuallyBoundEvidenceAsOnePlan() {
        CanvasState current = CanvasState.builder().userId("alice").diagramId("diagram-1")
                .diagramType("flowchart").currentXml(BEFORE).contentHash("hash-1").version(1L).build();
        ICanvasStateStore readStore = new ICanvasStateStore() {
            @Override public Optional<CanvasState> find(String userId, String diagramId) { return Optional.of(current); }
            @Override public CanvasState save(CanvasState state) { throw new AssertionError("commit must use atomic port"); }
        };
        AtomicReference<GroundedCanvasCommitPort.CommitPlan> captured = new AtomicReference<>();
        GroundedCanvasCommitPort port = new GroundedCanvasCommitPort() {
            @Override public java.util.Map<String, InheritedProvenance> findPersistedProvenance(InheritanceQuery query) {
                return java.util.Map.of("existing", new InheritedProvenance("prv_trusted", SupportType.EVIDENCE));
            }
            @Override public CanvasStateSaveResult commit(CommitPlan plan) {
                captured.set(plan);
                return CanvasStateSaveResult.updated(CanvasState.builder().userId("alice").diagramId("diagram-1")
                        .diagramType("flowchart").currentXml(plan.canvasXml()).contentHash("hash-2").version(2L).build());
            }
        };
        CanvasCommitModule module = new CanvasCommitModule(
                new CanvasMutationGate(readStore, new DefaultCanvasAnalyzer()),
                new CitationGuard(requests -> List.of()), port);
        EvidenceAccessContext access = EvidenceAccessContext.from(new EvidenceBundle(
                "bundle-1", "request-1", "run-1", SourceMode.EXPLICIT_ONLY,
                List.of(item("E1", "evidence-used"), item("E2", "evidence-unused"))), false);
        CitationBinding binding = new CitationBinding("node-1", "D1", StatementKind.NODE_TEXT,
                "Product Owner is accountable for maximizing value", null, null, List.of("E1"),
                List.of(new SupportAtom("A1", "E1",
                        "Product Owner is accountable for maximizing value", SupportAtomRole.PREMISE)),
                SupportType.EVIDENCE);
        RunResourceDomain resources = new RunResourceDomain();
        resources.markPrepared();

        CanvasCommitResult result = module.commit(new CanvasCommitCommand(
                new CanvasMutationCommand(CanvasMutationPurpose.USER_CREATE, BEFORE, AFTER, DiagramType.FLOWCHART,
                        CanvasMutationAuthorization.unrestricted(), "alice", "diagram-1", 1L, "hash-1"),
                "request-1", "run-1", access, List.of(binding), true, true), resources);

        assertTrue(result.committed(), result.errors().toString());
        assertEquals(RunResourceState.CLOSED, resources.state());
        assertEquals(CloseReason.COMMITTED, resources.closeReason().orElseThrow());
        assertNotNull(captured.get());
        assertTrue(captured.get().canvasXml().contains("zippCitationSchema=\"1\""));
        assertFalse(captured.get().canvasXml().contains("model-forged"));
        assertTrue(captured.get().canvasXml().contains("zippProvenanceRef=\"prv_trusted\""));
        String reopened = new DrawioCanvasXmlToolkit().toGraphModel(captured.get().canvasXml());
        assertTrue(reopened.contains("zippProvenanceRef=\"prv_trusted\""));
        assertEquals(java.util.Set.of("existing"), captured.get().inheritedCellIds());
        assertEquals(List.of("evidence-used"), captured.get().citations().get(0).evidenceLinks().stream()
                .map(GroundedCanvasCommitPort.EvidenceLink::evidenceId).toList());
    }

    @Test
    void rejectsMalformedManifestEvenWhenEvidenceModeIsNotStrict() {
        ICanvasStateStore readStore = new ICanvasStateStore() {
            @Override public Optional<CanvasState> find(String userId, String diagramId) { return Optional.empty(); }
            @Override public CanvasState save(CanvasState state) { throw new AssertionError(); }
        };
        CanvasCommitModule module = new CanvasCommitModule(
                new CanvasMutationGate(readStore, new DefaultCanvasAnalyzer()),
                new CitationGuard(requests -> List.of()), new GroundedCanvasCommitPort() {
                    @Override public java.util.Map<String, InheritedProvenance> findPersistedProvenance(InheritanceQuery query) {
                        return java.util.Map.of();
                    }
                    @Override public CanvasStateSaveResult commit(CommitPlan plan) {
                        throw new AssertionError("must not commit");
                    }
                });
        EvidenceAccessContext access = EvidenceAccessContext.from(new EvidenceBundle(
                "bundle-1", "request-1", "run-1", SourceMode.AUTO, List.of()), true);
        RunResourceDomain resources = new RunResourceDomain();
        resources.markPrepared();

        CanvasCommitResult result = module.commit(new CanvasCommitCommand(
                new CanvasMutationCommand(CanvasMutationPurpose.USER_CREATE, BEFORE, AFTER, DiagramType.FLOWCHART,
                        CanvasMutationAuthorization.unrestricted(), "alice", "diagram-1", null, null),
                "request-1", "run-1", access, List.of(), false, false), resources);

        assertFalse(result.committed());
        assertEquals(List.of("CITATION_MANIFEST_INVALID"), result.errors());
    }

    @Test
    void rejectsRetrievedCandidateThatChangesAnImmutableDirectCell() {
        ICanvasStateStore readStore = new ICanvasStateStore() {
            @Override public Optional<CanvasState> find(String userId, String diagramId) {
                return Optional.empty();
            }
            @Override public CanvasState save(CanvasState state) { throw new AssertionError(); }
        };
        CanvasCommitModule module = new CanvasCommitModule(
                new CanvasMutationGate(readStore, new DefaultCanvasAnalyzer()),
                new CitationGuard(requests -> List.of()), new GroundedCanvasCommitPort() {
                    @Override public java.util.Map<String, InheritedProvenance> findPersistedProvenance(
                            InheritanceQuery query) {
                        return java.util.Map.of();
                    }
                    @Override public CanvasStateSaveResult commit(CommitPlan plan) {
                        throw new AssertionError("conflicting direct cell must not commit");
                    }
                });
        String direct = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="direct-node-a" value="Original" vertex="1" parent="1">
                <mxGeometry x="0" y="0" width="180" height="60" as="geometry"/></mxCell>
                </root></mxGraphModel>
                """;
        String overwritten = direct.replace("value=\"Original\"", "value=\"Retrieved rewrite\"");
        EvidenceAccessContext access = EvidenceAccessContext.from(new EvidenceBundle(
                "bundle-1", "request-1", "run-1", SourceMode.EXPLICIT_ONLY,
                List.of(item("E1", "evidence-used"))), false);
        RunResourceDomain resources = new RunResourceDomain();
        resources.markPrepared();

        CanvasCommitResult result = module.commit(new CanvasCommitCommand(
                new CanvasMutationCommand(CanvasMutationPurpose.USER_CREATE, direct, overwritten,
                        DiagramType.FLOWCHART, CanvasMutationAuthorization.unrestricted(),
                        "alice", "diagram-1", null, null),
                "request-1", "run-1", access, List.of(), true, true,
                java.util.Set.of("direct-node-a")), resources);

        assertFalse(result.committed());
        assertEquals(List.of("DIRECT_SOURCE_CONFLICT"), result.errors());
    }

    private EvidenceBundleItem item(String key, String evidenceId) {
        return new EvidenceBundleItem(key, evidenceId, "material-1", "version-1", "revision-1",
                "S1", 6, "TEXT", "Product Owner is accountable for maximizing value.");
    }
}
