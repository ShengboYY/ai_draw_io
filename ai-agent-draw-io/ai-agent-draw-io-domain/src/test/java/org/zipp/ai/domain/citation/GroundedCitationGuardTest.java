package org.zipp.ai.domain.citation;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.citation.model.valobj.*;
import org.zipp.ai.domain.citation.port.ClaimSupportVerifierPort;
import org.zipp.ai.domain.citation.service.CitationGuard;
import org.zipp.ai.domain.grounding.EvidenceAccessContext;
import org.zipp.ai.domain.retrieval.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroundedCitationGuardTest {
    private static final String XML = """
            <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
            <mxCell id="node-1" value="Product Owner is accountable for maximizing value" vertex="1" parent="1"/>
            </root></mxGraphModel>
            """;

    @Test
    void acceptsExactDisplaySupportWithoutCallingSemanticVerifier() {
        ClaimSupportVerifierPort verifier = requests -> {
            fail("exact display support must use the deterministic fast path");
            return List.of();
        };
        CitationGuard guard = new CitationGuard(verifier);
        CitationBinding binding = binding("E1",
                "Product Owner is accountable for maximizing value",
                "Product Owner is accountable for maximizing value");

        CitationGuardResult result = guard.validate(XML, List.of(binding), context(SourceMode.EXPLICIT_ONLY), true);

        assertTrue(result.accepted());
        assertEquals(1, result.bindings().size());
    }

    @Test
    void rejectsCitationOutsideBundleAndForgedAnchor() {
        CitationGuard guard = new CitationGuard(requests -> List.of());

        CitationGuardResult outside = guard.validate(XML,
                List.of(binding("E9", "Product Owner is accountable for maximizing value",
                        "Product Owner is accountable for maximizing value")),
                context(SourceMode.EXPLICIT_ONLY), true);
        CitationGuardResult forged = guard.validate(XML,
                List.of(binding("E1", "Product Owner is accountable for maximizing value",
                        "This text does not occur in evidence")),
                context(SourceMode.EXPLICIT_ONLY), true);

        assertFalse(outside.accepted());
        assertTrue(outside.errors().contains("CITATION_KEY_OUTSIDE_BUNDLE"));
        assertFalse(forged.accepted());
        assertTrue(forged.errors().contains("SUPPORT_ATOM_NOT_CONTIGUOUS"));
    }

    @Test
    void synthesizedStatementFailsClosedWhenVerifierDoesNotEntailIt() {
        ClaimSupportVerifierPort verifier = requests -> List.of(
                new ClaimSupportVerifierPort.Result("D1", ClaimSupportVerdict.NOT_ENTAILED));
        CitationGuard guard = new CitationGuard(verifier);
        CitationBinding binding = binding("E1",
                "The Product Owner is solely accountable for maximizing all product value.",
                "Product Owner is accountable for maximizing value");

        CitationGuardResult result = guard.validate(XML, List.of(binding), context(SourceMode.EXPLICIT_ONLY), true);

        assertFalse(result.accepted());
        assertTrue(result.errors().contains("CLAIM_NOT_ENTAILED"));
    }

    @Test
    void strictModeRejectsAiKnowledgeFacts() {
        CitationBinding binding = new CitationBinding("node-1", "D1", StatementKind.NODE_TEXT,
                "Product Owner is accountable for maximizing value", null, null,
                List.of(), List.of(), SupportType.AI_KNOWLEDGE);

        CitationGuardResult result = new CitationGuard(requests -> List.of())
                .validate(XML, List.of(binding), context(SourceMode.EXPLICIT_ONLY), true);

        assertFalse(result.accepted());
        assertTrue(result.errors().contains("AI_KNOWLEDGE_FORBIDDEN"));
    }

    @Test
    void strictModeDoesNotCountNoneAsGroundedAndRejectsMixedCellSupport() {
        CitationBinding none = new CitationBinding("node-1", "D-none", StatementKind.NODE_TEXT,
                "Product Owner is accountable for maximizing value", null, null,
                List.of(), List.of(), SupportType.NONE);
        CitationBinding evidence = binding("E1",
                "Product Owner is accountable for maximizing value",
                "Product Owner is accountable for maximizing value");
        CitationGuard guard = new CitationGuard(requests -> List.of());

        CitationGuardResult strictNone = guard.validate(
                XML, List.of(none), context(SourceMode.EXPLICIT_ONLY), true);
        CitationGuardResult mixed = guard.validate(
                XML, List.of(evidence, none), context(SourceMode.AUTO), false);

        assertFalse(strictNone.accepted());
        assertTrue(strictNone.errors().contains("STRICT_NON_EVIDENCE_BINDING"));
        assertTrue(strictNone.errors().contains("STRICT_FACTUAL_CELL_UNBOUND"));
        assertFalse(mixed.accepted());
        assertTrue(mixed.errors().contains("INCONSISTENT_CELL_SUPPORT_TYPE"));
    }

    @Test
    void contextOnlyEvidenceCannotBePersistedAsClaimSupport() {
        EvidenceAccessContext access = EvidenceAccessContext.from(new EvidenceBundle(
                "bundle-1", "request-1", "run-1", SourceMode.AUTO,
                List.of(new EvidenceBundleItem("E1", "evidence-1", "material-1", "version-1", "revision-1",
                        "S1", 6, "TEXT", "Product Owner is accountable for maximizing value.",
                        EvidenceSupportRole.CONTEXT_ONLY))), true);

        CitationGuardResult result = new CitationGuard(requests -> List.of())
                .validate(XML, List.of(binding("E1",
                        "Product Owner is accountable for maximizing value",
                        "Product Owner is accountable for maximizing value")), access, false);

        assertFalse(result.accepted());
        assertTrue(result.errors().contains("CONTEXT_ONLY_CITATION_FORBIDDEN"));
    }

    private CitationBinding binding(String citationKey, String statement, String anchor) {
        return new CitationBinding("node-1", "D1", StatementKind.NODE_TEXT, statement,
                null, null, List.of(citationKey),
                List.of(new SupportAtom("A1", citationKey, anchor, SupportAtomRole.PREMISE)),
                SupportType.EVIDENCE);
    }

    private EvidenceAccessContext context(SourceMode mode) {
        return EvidenceAccessContext.from(new EvidenceBundle("bundle-1", "request-1", "run-1", mode,
                List.of(new EvidenceBundleItem("E1", "evidence-1", "material-1", "version-1", "revision-1",
                        "S1", 6, "TEXT", "Product Owner is accountable for maximizing value."))), false);
    }
}
