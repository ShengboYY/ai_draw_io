package org.zipp.ai.domain.citation.answer;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.citation.model.valobj.ClaimSupportVerdict;
import org.zipp.ai.domain.citation.model.valobj.SupportAtom;
import org.zipp.ai.domain.citation.model.valobj.SupportAtomRole;
import org.zipp.ai.domain.grounding.EvidenceAccessContext;
import org.zipp.ai.domain.retrieval.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceAnswerGuardTest {

    @Test
    void acceptsDirectExtractiveClaimWithoutCallingSemanticVerifier() {
        AtomicInteger calls = new AtomicInteger();
        EvidenceAnswerGuard guard = new EvidenceAnswerGuard(requests -> {
            calls.incrementAndGet();
            return List.of();
        });

        EvidenceAnswerGuard.Result result = guard.validate(proposal(
                claim("C1", "Teams inspect progress every day", AnswerSupportType.DIRECT,
                        List.of("E1"), atom("E1", "Teams inspect progress every day"))), access());

        assertTrue(result.accepted());
        assertEquals(1, result.claims().size());
        assertEquals(0, calls.get());
    }

    @Test
    void directClaimWithUnrelatedSecondCitationIsRejected() {
        AtomicInteger calls = new AtomicInteger();
        EvidenceAnswerGuard guard = new EvidenceAnswerGuard(requests -> {
            calls.incrementAndGet();
            return List.of(new org.zipp.ai.domain.citation.port.ClaimSupportVerifierPort.Result(
                    "C1", ClaimSupportVerdict.NOT_ENTAILED));
        });
        AnswerClaim claim = claim("C1", "Teams inspect progress every day", AnswerSupportType.DIRECT,
                List.of("E1", "E2"), List.of(
                        new SupportAtom("A1", "E1", "Teams inspect progress every day", SupportAtomRole.DIRECT_QUOTE),
                        new SupportAtom("A2", "E2", "Scrum uses timeboxes", SupportAtomRole.PREMISE)));
        EvidenceBundle bundle = new EvidenceBundle("bundle", "request", "run", SourceMode.AUTO, List.of(
                item(), new EvidenceBundleItem("E2", "ev-2", "mat-2", "v2", "r2",
                        "Guide 2", 5, "TEXT", "Scrum uses timeboxes")));

        EvidenceAnswerGuard.Result result = guard.validate(proposal(claim),
                EvidenceAccessContext.from(bundle, false));

        assertFalse(result.accepted());
        assertEquals(0, calls.get());
        assertTrue(result.errors().contains("DIRECT_CITATION_NOT_EXACT"));
    }

    @Test
    void visualVerifiedIsRejectedUntilAuthenticatedVisualObservationsExist() {
        EvidenceAnswerGuard guard = new EvidenceAnswerGuard(requests -> List.of(
                new org.zipp.ai.domain.citation.port.ClaimSupportVerifierPort.Result(
                        "C1", ClaimSupportVerdict.ENTAILED)));
        AnswerClaim claim = claim("C1", "Teams inspect progress every day",
                AnswerSupportType.VISUAL_VERIFIED, List.of("E1"),
                atom("E1", "Teams inspect progress every day"));

        EvidenceAnswerGuard.Result result = guard.validate(proposal(claim), access());

        assertFalse(result.accepted());
        assertTrue(result.errors().contains("VISUAL_VERIFICATION_UNAVAILABLE"));
    }

    @Test
    void failsClosedWhenSynthesizedClaimIsNotFullyEntailed() {
        EvidenceAnswerGuard guard = new EvidenceAnswerGuard(requests -> List.of(
                new org.zipp.ai.domain.citation.port.ClaimSupportVerifierPort.Result(
                        "C1", ClaimSupportVerdict.PARTIAL)));

        EvidenceAnswerGuard.Result result = guard.validate(proposal(
                claim("C1", "Daily inspection guarantees delivery", AnswerSupportType.SYNTHESIZED,
                        List.of("E1"), atom("E1", "Teams inspect progress every day"))), access());

        assertFalse(result.accepted());
        assertTrue(result.errors().contains("CLAIM_NOT_ENTAILED"));
    }

    @Test
    void synthesizedClaimAlwaysUsesSemanticVerifierEvenWhenItsTextMatchesAnAnchor() {
        AtomicInteger calls = new AtomicInteger();
        EvidenceAnswerGuard guard = new EvidenceAnswerGuard(requests -> {
            calls.incrementAndGet();
            return List.of(new org.zipp.ai.domain.citation.port.ClaimSupportVerifierPort.Result(
                    "C1", ClaimSupportVerdict.ENTAILED));
        });

        EvidenceAnswerGuard.Result result = guard.validate(proposal(
                claim("C1", "Teams inspect progress every day", AnswerSupportType.SYNTHESIZED,
                        List.of("E1"), atom("E1", "Teams inspect progress every day"))), access());

        assertTrue(result.accepted());
        assertEquals(1, calls.get());
    }

    @Test
    void contextOnlyEvidenceCannotSupportAnAnswerClaim() {
        EvidenceBundle bundle = new EvidenceBundle("bundle", "request", "run", SourceMode.AUTO,
                List.of(new EvidenceBundleItem("E1", "ev-1", "mat-1", "v1", "r1",
                        "Guide", 4, "TEXT", "Teams inspect progress every day",
                        EvidenceSupportRole.CONTEXT_ONLY)));

        EvidenceAnswerGuard.Result result = new EvidenceAnswerGuard(requests -> List.of()).validate(
                proposal(claim("C1", "Teams inspect progress every day", AnswerSupportType.DIRECT,
                        List.of("E1"), atom("E1", "Teams inspect progress every day"))),
                EvidenceAccessContext.from(bundle, false));

        assertFalse(result.accepted());
        assertTrue(result.errors().contains("CONTEXT_ONLY_CITATION_FORBIDDEN"));
    }

    @Test
    void aiKnowledgeIsForbiddenForExplicitOnlyRequests() {
        EvidenceBundle explicitBundle = new EvidenceBundle("bundle", "request", "run",
                SourceMode.EXPLICIT_ONLY, List.of(item()));
        AnswerClaim claim = claim("C1", "General model knowledge", AnswerSupportType.AI_KNOWLEDGE,
                List.of(), List.of());

        EvidenceAnswerGuard.Result result = new EvidenceAnswerGuard(requests -> List.of())
                .validate(proposal(claim), EvidenceAccessContext.from(explicitBundle, true));

        assertFalse(result.accepted());
        assertTrue(result.errors().contains("AI_KNOWLEDGE_FORBIDDEN"));
    }

    private AnswerProposal proposal(AnswerClaim claim) {
        return new AnswerProposal(List.of(claim), List.of(), List.of());
    }

    private AnswerClaim claim(String key, String text, AnswerSupportType supportType,
                              List<String> citationKeys, List<SupportAtom> atoms) {
        return new AnswerClaim(key, text, citationKeys, supportType, atoms);
    }

    private List<SupportAtom> atom(String citationKey, String anchor) {
        return List.of(new SupportAtom("A1", citationKey, anchor, SupportAtomRole.DIRECT_QUOTE));
    }

    private EvidenceAccessContext access() {
        return EvidenceAccessContext.from(new EvidenceBundle("bundle", "request", "run",
                SourceMode.AUTO, List.of(item())), false);
    }

    private EvidenceBundleItem item() {
        return new EvidenceBundleItem("E1", "ev-1", "mat-1", "v1", "r1",
                "Guide", 4, "TEXT", "Teams inspect progress every day");
    }
}
