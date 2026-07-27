package org.zipp.ai.domain.grounding;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.citation.model.valobj.CitationBinding;
import org.zipp.ai.domain.citation.model.valobj.StatementKind;
import org.zipp.ai.domain.citation.model.valobj.SupportAtom;
import org.zipp.ai.domain.citation.model.valobj.SupportAtomRole;
import org.zipp.ai.domain.citation.model.valobj.SupportType;
import org.zipp.ai.domain.multimodal.DirectSourceOutcome;
import org.zipp.ai.domain.multimodal.ObservationBounds;
import org.zipp.ai.domain.multimodal.ObservedDiagramGraph;
import org.zipp.ai.domain.retrieval.EvidenceBundle;
import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import org.zipp.ai.domain.retrieval.EvidenceOrigin;
import org.zipp.ai.domain.retrieval.EvidenceSupportRole;
import org.zipp.ai.domain.retrieval.PreparedEvidence;
import org.zipp.ai.domain.retrieval.RunResourceDomain;
import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DirectAndRetrievalEvidenceComposerTest {

    @Test
    void combinesDirectAndRetrievedEvidenceWithoutAllowingAiKnowledge() {
        DirectAndRetrievalEvidenceComposer.Outcome.Ready ready = assertInstanceOf(
                DirectAndRetrievalEvidenceComposer.Outcome.Ready.class,
                new DirectAndRetrievalEvidenceComposer().compose(direct("run-1", "direct-1"),
                        retrieval("run-1", "cite-1", EvidenceOrigin.EXPLICIT)));

        assertEquals(List.of(EvidenceOrigin.DIRECT_ATTACHMENT, EvidenceOrigin.EXPLICIT),
                ready.evidenceAccess().items().stream().map(EvidenceBundleItem::origin).toList());
        assertEquals(List.of("direct-1", "cite-1"),
                ready.evidenceAccess().items().stream()
                        .map(EvidenceBundleItem::citationKey).toList());
        assertFalse(ready.evidenceAccess().aiKnowledgeAllowed());
        assertEquals(java.util.Set.of("direct-node-a"), ready.immutableDirectCellIds());
        assertEquals(List.of("direct-node-a"),
                ready.directBindings().stream().map(CitationBinding::cellId).toList());
    }

    @Test
    void rejectsCrossRunAndCitationKeyCollisions() {
        DirectAndRetrievalEvidenceComposer.Outcome.Conflict conflict = assertInstanceOf(
                DirectAndRetrievalEvidenceComposer.Outcome.Conflict.class,
                new DirectAndRetrievalEvidenceComposer().compose(direct("run-1", "direct-1"),
                        retrieval("run-2", "direct-1", EvidenceOrigin.SEARCH)));

        assertEquals(List.of("RUN_ID_MISMATCH", "CITATION_KEY_COLLISION"), conflict.reasons());
    }

    @Test
    void rejectsRetrievedEvidenceMasqueradingAsDirectImageContent() {
        DirectAndRetrievalEvidenceComposer.Outcome.Conflict conflict = assertInstanceOf(
                DirectAndRetrievalEvidenceComposer.Outcome.Conflict.class,
                new DirectAndRetrievalEvidenceComposer().compose(direct("run-1", "direct-1"),
                        retrieval("run-1", "cite-1", EvidenceOrigin.DIRECT_ATTACHMENT)));

        assertEquals(List.of("RETRIEVAL_CONTAINS_DIRECT_ORIGIN"), conflict.reasons());
    }

    private DirectSourceOutcome.Prepared direct(String runId, String citationKey) {
        ObservedDiagramGraph graph = new ObservedDiagramGraph(
                List.of(new ObservedDiagramGraph.Node(
                        "a", "A", ObservedDiagramGraph.Shape.RECTANGLE,
                        new ObservationBounds(0.1, 0.1, 0.2, 0.1),
                        "", "evidence-direct", 0.99)),
                List.of(), List.of(), List.of());
        EvidenceBundleItem item = item(citationKey, "evidence-direct",
                EvidenceOrigin.DIRECT_ATTACHMENT);
        EvidenceAccessContext access = EvidenceAccessContext.from(new EvidenceBundle(
                "direct-bundle", "request-1", runId, SourceMode.EXPLICIT_ONLY,
                List.of(item)), false);
        CitationBinding binding = new CitationBinding(
                "direct-node-a", "direct-statement-a", StatementKind.NODE_TEXT,
                "A", null, null, List.of(citationKey),
                List.of(new SupportAtom("direct-atom-a", citationKey, "A",
                        SupportAtomRole.DIRECT_QUOTE)), SupportType.EVIDENCE);
        return new DirectSourceOutcome.Prepared(
                graph,
                "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                        + "<mxCell id=\"direct-node-a\" value=\"A\" vertex=\"1\" parent=\"1\"/>"
                        + "</root></mxGraphModel>",
                List.of("direct-node-a"),
                access,
                List.of(binding));
    }

    private PreparedEvidence retrieval(String runId, String citationKey, EvidenceOrigin origin) {
        EvidenceBundle bundle = new EvidenceBundle(
                "retrieval-bundle", "request-1", runId, SourceMode.EXPLICIT_ONLY,
                List.of(item(citationKey, "evidence-retrieved", origin)));
        return new PreparedEvidence(bundle, new RunResourceDomain());
    }

    private EvidenceBundleItem item(String citationKey, String evidenceId, EvidenceOrigin origin) {
        return new EvidenceBundleItem(
                citationKey, evidenceId, "material-1", "version-1", "revision-1",
                "source", 1, "TEXT", "A", EvidenceSupportRole.SUPPORT, origin);
    }
}
