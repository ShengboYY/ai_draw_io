package org.zipp.ai.domain.citation;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.canvas.*;
import org.zipp.ai.domain.citation.port.ManualProvenancePort;
import org.zipp.ai.domain.citation.model.valobj.SupportType;
import org.zipp.ai.domain.citation.service.ManualCitationReconciler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ManualCitationReconcilerTest {
    @Test
    void preservesPresentationOnlyCellsAndMarksSemanticChangesManual() {
        AtomicReference<ManualProvenancePort.ManualReconciliationPlan> captured = new AtomicReference<>();
        ManualCitationReconciler reconciler = new ManualCitationReconciler(new ManualProvenancePort() {
            @Override public Map<String, StoredProvenance> findProvenance(String ownerKey, String diagramId, long canvasVersion) {
                return Map.of();
            }
            @Override public void reconcile(ManualReconciliationPlan plan) { captured.set(plan); }
        });
        CanvasAnalysis after = CanvasAnalysis.builder().cells(List.of(
                CanvasCellData.builder().id("styled").label("Same fact").kind("vertex").build(),
                CanvasCellData.builder().id("changed").label("Human rewrite").kind("vertex").build())).build();
        CanvasState saved = CanvasState.builder().userId("alice").diagramId("diagram-1")
                .currentXml("<mxGraphModel/>").contentHash("hash-2").version(2L).build();
        CanvasMutationDecision decision = new CanvasMutationDecision(
                CanvasMutationStatus.ACCEPTED, saved.getCurrentXml(), null, after,
                Set.of("styled", "changed", "deleted"),
                Map.of("styled", Set.of(CanvasField.STYLE, CanvasField.GEOMETRY),
                        "changed", Set.of(CanvasField.VALUE),
                        "deleted", Set.of(CanvasField.DELETE_CELL)),
                null, CanvasStateSaveResult.updated(saved), saved);

        reconciler.reconcile(decision);

        ManualProvenancePort.ManualReconciliationPlan plan = captured.get();
        assertNotNull(plan);
        assertEquals(1L, plan.previousVersion());
        assertEquals(Set.of("changed"), plan.manualProvenance().keySet());
        assertEquals(List.of("deleted"), plan.removedCellIds());
        assertFalse(plan.manualProvenance().containsKey("styled"));
        assertEquals(64, plan.manualProvenance().get("changed").semanticHash().length());
    }

    @Test
    void rebuildsManualMetadataAndDiscardsClientForgedAttributes() {
        ManualCitationReconciler reconciler = new ManualCitationReconciler(new ManualProvenancePort() {
            @Override public Map<String, StoredProvenance> findProvenance(String ownerKey, String diagramId, long canvasVersion) {
                return Map.of("same", new StoredProvenance("prv_existing12345678", SupportType.EVIDENCE));
            }
            @Override public void reconcile(ManualReconciliationPlan plan) { }
        });
        String xml = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="same" value="Fact" zippProvenanceRef="forged" vertex="1" parent="1"/>
                <mxCell id="changed" value="Rewrite" zippSupportType="EVIDENCE" vertex="1" parent="1"/>
                </root></mxGraphModel>
                """;
        CanvasAnalysis after = CanvasAnalysis.builder().cells(List.of(
                CanvasCellData.builder().id("same").label("Fact").kind("node").build(),
                CanvasCellData.builder().id("changed").label("Rewrite").kind("node").build())).build();
        CanvasMutationDecision assessment = new CanvasMutationDecision(
                CanvasMutationStatus.ACCEPTED, xml, null, after, Set.of("changed"),
                Map.of("changed", Set.of(CanvasField.VALUE)), null, null, null);
        CanvasMutationCommand command = new CanvasMutationCommand(CanvasMutationPurpose.USER_EDIT, xml, xml,
                null, CanvasMutationAuthorization.unrestricted(), "alice", "diagram-1", 1L, "hash-1");

        CanvasMutationCommand rebuilt = reconciler.rebuildServerMetadata(command, assessment);

        assertFalse(rebuilt.candidateXml().contains("forged"));
        assertTrue(rebuilt.candidateXml().contains("zippSupportType=\"EVIDENCE\""));
        assertTrue(rebuilt.candidateXml().contains("zippSupportType=\"MANUAL\""));
    }

    @Test
    void preservesAnImportedOpaqueReferenceOnlyWhenOwnerResolutionAuthorizesItsCitation() {
        String importedRef = "prv_0123456789abcdef01234567";
        ManualCitationReconciler reconciler = new ManualCitationReconciler(new ManualProvenancePort() {
            @Override public Map<String, StoredProvenance> findProvenance(
                    String ownerKey, String diagramId, long canvasVersion) { return Map.of(); }
            @Override public java.util.Optional<StoredProvenance> findOwnedProvenance(
                    String ownerKey, String provenanceRef) {
                return importedRef.equals(provenanceRef)
                        ? java.util.Optional.of(new StoredProvenance(
                                importedRef, SupportType.EVIDENCE, "cit-1", sha256("Fact")))
                        : java.util.Optional.empty();
            }
            @Override public void reconcile(ManualReconciliationPlan plan) { }
        });
        String xml = "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + "<mxCell id=\"imported\" value=\"Fact\" zippProvenanceRef=\"" + importedRef
                + "\" zippSupportType=\"EVIDENCE\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>";
        CanvasAnalysis after = CanvasAnalysis.builder().cells(List.of(
                CanvasCellData.builder().id("imported").label("Fact").kind("node").build())).build();
        CanvasMutationDecision assessment = new CanvasMutationDecision(
                CanvasMutationStatus.ACCEPTED, xml, null, after, Set.of("imported"),
                Map.of("imported", Set.of(CanvasField.ADD_CELL)), null, null, null);

        CanvasMutationCommand rebuilt = reconciler.rebuildServerMetadata(new CanvasMutationCommand(
                CanvasMutationPurpose.USER_EDIT, "<mxGraphModel/>", xml, null,
                CanvasMutationAuthorization.unrestricted(), "alice", "diagram-new", 0L, "hash-0"), assessment);

        assertTrue(rebuilt.candidateXml().contains("zippProvenanceRef=\"" + importedRef + "\""));
        assertTrue(rebuilt.candidateXml().contains("zippSupportType=\"EVIDENCE\""));
    }

    @Test
    void mismatchedImportedReferenceCannotClaimEvidenceSupport() {
        String foreignRef = "prv_aaaaaaaaaaaaaaaaaaaaaaaa";
        ManualCitationReconciler reconciler = new ManualCitationReconciler(new ManualProvenancePort() {
            @Override public Map<String, StoredProvenance> findProvenance(
                    String ownerKey, String diagramId, long canvasVersion) { return Map.of(); }
            @Override public java.util.Optional<StoredProvenance> findOwnedProvenance(
                    String ownerKey, String provenanceRef) {
                return java.util.Optional.of(new StoredProvenance(
                        foreignRef, SupportType.EVIDENCE, "cit-foreign", sha256("Different claim")));
            }
            @Override public void reconcile(ManualReconciliationPlan plan) { }
        });
        String xml = "<mxGraphModel><root><mxCell id=\"foreign\" value=\"Fact\" "
                + "zippProvenanceRef=\"" + foreignRef + "\" zippSupportType=\"EVIDENCE\" "
                + "vertex=\"1\" parent=\"1\"/></root></mxGraphModel>";
        CanvasAnalysis after = CanvasAnalysis.builder().cells(List.of(
                CanvasCellData.builder().id("foreign").label("Fact").kind("node").build())).build();
        CanvasMutationDecision assessment = new CanvasMutationDecision(
                CanvasMutationStatus.ACCEPTED, xml, null, after, Set.of("foreign"),
                Map.of("foreign", Set.of(CanvasField.ADD_CELL)), null, null, null);

        CanvasMutationCommand rebuilt = reconciler.rebuildServerMetadata(new CanvasMutationCommand(
                CanvasMutationPurpose.USER_EDIT, "<mxGraphModel/>", xml, null,
                CanvasMutationAuthorization.unrestricted(), "bob", "diagram-new", 0L, "hash-0"), assessment);

        assertTrue(rebuilt.candidateXml().contains("zippProvenanceRef=\"" + foreignRef + "\""));
        assertTrue(rebuilt.candidateXml().contains("zippSupportType=\"UNATTRIBUTED\""));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
