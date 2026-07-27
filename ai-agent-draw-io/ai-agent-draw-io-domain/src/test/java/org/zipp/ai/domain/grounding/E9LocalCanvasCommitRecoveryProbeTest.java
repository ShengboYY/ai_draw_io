package org.zipp.ai.domain.grounding;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationAuthorization;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationCommand;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationPurpose;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateSaveResult;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.canvas.CanvasMutationGate;
import org.zipp.ai.domain.citation.model.valobj.CitationBinding;
import org.zipp.ai.domain.citation.model.valobj.StatementKind;
import org.zipp.ai.domain.citation.model.valobj.SupportAtom;
import org.zipp.ai.domain.citation.model.valobj.SupportAtomRole;
import org.zipp.ai.domain.citation.model.valobj.SupportType;
import org.zipp.ai.domain.citation.service.CitationGuard;
import org.zipp.ai.domain.grounding.port.GroundedCanvasCommitPort;
import org.zipp.ai.domain.retrieval.EvidenceBundle;
import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import org.zipp.ai.domain.retrieval.RunResourceDomain;
import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the public grounded commit seam against a recoverable synthetic persistence outage. */
class E9LocalCanvasCommitRecoveryProbeTest {
    private static final String BEFORE = """
            <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
            </root></mxGraphModel>
            """;
    private static final String AFTER = """
            <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
            <mxCell id="node-e9" value="Product Owner maximizes value" vertex="1" parent="1">
            <mxGeometry x="0" y="0" width="180" height="60" as="geometry"/></mxCell>
            </root></mxGraphModel>
            """;

    @Test
    void staleCanvasIsRejectedBeforeTheSavePortIsCalled() {
        AtomicInteger commits = new AtomicInteger();
        CanvasCommitModule module = module(false, commits);
        CanvasCommitResult result = module.commit(command(0L, "hash-e9"), new RunResourceDomain());

        assertFalse(result.committed());
        assertEquals(0, commits.get());
        assertTrue(result.errors().contains("VERSION_MISMATCH"));
    }

    @Test
    void saveFailureDoesNotCommitAndASeparateRetryCanCommitAfterRecovery() {
        AtomicBoolean saveDown = new AtomicBoolean(true);
        AtomicInteger commits = new AtomicInteger();
        CanvasCommitModule module = module(saveDown, commits);

        CanvasCommitResult failed = module.commit(command(1L, "hash-e9"), preparedResources());
        assertFalse(failed.committed());
        assertEquals(List.of("CANVAS_COMMIT_FAILED"), failed.errors());
        assertEquals(1, commits.get());

        saveDown.set(false);
        CanvasCommitResult recovered = module.commit(command(1L, "hash-e9"), preparedResources());
        assertTrue(recovered.committed());
        assertEquals(2, commits.get());
    }

    private CanvasCommitModule module(boolean saveDown, AtomicInteger commits) {
        return module(new AtomicBoolean(saveDown), commits);
    }

    private CanvasCommitModule module(AtomicBoolean saveDown, AtomicInteger commits) {
        CanvasState current = CanvasState.builder().userId("e9-probe-owner").diagramId("diagram-e9")
                .diagramType("flowchart").currentXml(BEFORE).contentHash("hash-e9").version(1L).build();
        ICanvasStateStore store = new ICanvasStateStore() {
            @Override public Optional<CanvasState> find(String userId, String diagramId) {
                return Optional.of(current);
            }
            @Override public CanvasState save(CanvasState state) { throw new AssertionError("atomic port required"); }
        };
        GroundedCanvasCommitPort port = new GroundedCanvasCommitPort() {
            @Override public Map<String, InheritedProvenance> findPersistedProvenance(InheritanceQuery query) {
                return Map.of();
            }
            @Override public CanvasStateSaveResult commit(CommitPlan plan) {
                commits.incrementAndGet();
                if (saveDown.get()) throw new IllegalStateException("synthetic canvas store outage");
                return CanvasStateSaveResult.updated(CanvasState.builder().userId("e9-probe-owner")
                        .diagramId("diagram-e9").diagramType("flowchart").currentXml(plan.canvasXml())
                        .contentHash(plan.contentHash()).version(2L).build());
            }
        };
        return new CanvasCommitModule(new CanvasMutationGate(store, new DefaultCanvasAnalyzer()),
                new CitationGuard(requests -> List.of()), port);
    }

    private CanvasCommitCommand command(long expectedVersion, String expectedHash) {
        EvidenceBundleItem item = new EvidenceBundleItem("E1", "evidence-e9-1", "material-e9",
                "version-e9-1", "revision-e9-1", "S1", 1, "TEXT",
                "Product Owner maximizes value.");
        EvidenceAccessContext access = EvidenceAccessContext.from(new EvidenceBundle("bundle-e9", "request-e9",
                "run-e9", SourceMode.EXPLICIT_ONLY, List.of(item)), false);
        CitationBinding binding = new CitationBinding("node-e9", "D1", StatementKind.NODE_TEXT,
                "Product Owner maximizes value", null, null, List.of("E1"),
                List.of(new SupportAtom("A1", "E1", "Product Owner maximizes value",
                        SupportAtomRole.PREMISE)), SupportType.EVIDENCE);
        return new CanvasCommitCommand(new CanvasMutationCommand(CanvasMutationPurpose.USER_CREATE, BEFORE, AFTER,
                DiagramType.FLOWCHART, CanvasMutationAuthorization.unrestricted(), "e9-probe-owner", "diagram-e9",
                expectedVersion, expectedHash), "request-e9", "run-e9", access, List.of(binding), true, true);
    }

    private RunResourceDomain preparedResources() {
        RunResourceDomain resources = new RunResourceDomain();
        resources.markPrepared();
        return resources;
    }
}
