package org.zipp.ai.application.memory;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.MatchedInstructionSpan;
import org.zipp.ai.application.turn.MemoryWriteRuleVersion;
import org.zipp.ai.application.turn.MemoryWriteSemanticDigest;
import org.zipp.ai.application.turn.NoMemoryWrite;
import org.zipp.ai.application.turn.RememberDecisionDeclaration;
import org.zipp.ai.application.turn.TurnKey;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class MemoryPolicySanitizerTest {
    private static final TurnKey TURN = new TurnKey("owner-1", "conversation-1", "turn-1");
    private static final RememberDecisionDeclaration DECLARATION = new RememberDecisionDeclaration(
            1, new MemoryWriteRuleVersion("MEMORY_V1"), new MatchedInstructionSpan("remember this"),
            new MemoryWriteSemanticDigest("semantic-1"), "chartbook-1");

    @Test
    void onlyExplicitConfirmedDecisionCanBeSanitized() {
        MemoryPolicySanitizer.SanitizationOutcome outcome = new MemoryPolicySanitizer().sanitize(
                command(new NoMemoryWrite(), "Prefer short labels"));

        MemoryPolicySanitizer.SanitizationOutcome.Rejected rejected =
                assertInstanceOf(MemoryPolicySanitizer.SanitizationOutcome.Rejected.class, outcome);
        assertEquals("MEMORY_EXPLICIT_CONFIRMATION_REQUIRED", rejected.code());
    }

    @Test
    void rejectedPayloadNeverAppearsInOutcomeOrStore() {
        String secret = "remember api_key=do-not-leak";
        MemoryPolicySanitizer.SanitizationOutcome outcome = new MemoryPolicySanitizer().sanitize(
                command(DECLARATION, secret));

        assertInstanceOf(MemoryPolicySanitizer.SanitizationOutcome.Rejected.class, outcome);
        assertEquals("MEMORY_SECRET_FORBIDDEN",
                ((MemoryPolicySanitizer.SanitizationOutcome.Rejected) outcome).code());
        assertNull(outcome.toString().contains(secret) ? secret : null);
    }

    @Test
    void acceptedValueIsBoundedContextOnlyPayload() {
        RecordingStore store = new RecordingStore();
        MemoryProposalOutcome outcome = new MemoryProposalService(store).propose(
                command(DECLARATION, "  Prefer   short labels  "));

        assertInstanceOf(MemoryProposalOutcome.Accepted.class, outcome);
        assertEquals(List.of("Prefer short labels"), store.payloads);
    }

    @Test
    void declarationDigestMustMatchTheOwnerFixedDeclaration() {
        MemoryProposalCommand command = new MemoryProposalCommand(
                TURN, "chartbook-1", "diagram-1", "candidate-1", "different", DECLARATION,
                "labels", "plain-generation", "Prefer short labels");

        MemoryPolicySanitizer.SanitizationOutcome.Rejected rejected = assertInstanceOf(
                MemoryPolicySanitizer.SanitizationOutcome.Rejected.class,
                new MemoryPolicySanitizer().sanitize(command));
        assertEquals("MEMORY_DECLARATION_DIGEST_CONFLICT", rejected.code());
    }

    private static MemoryProposalCommand command(
            org.zipp.ai.application.turn.MemoryWriteDeclaration declaration, String text) {
        return new MemoryProposalCommand(
                TURN, "chartbook-1", "diagram-1", "candidate-1",
                declaration instanceof RememberDecisionDeclaration remember
                        ? remember.digest().value() : "declaration-1", declaration,
                "labels", "plain-generation", text);
    }

    private static final class RecordingStore implements MemoryCandidateStorePort {
        private final List<String> payloads = new ArrayList<>();

        @Override
        public MemoryProposalOutcome propose(SanitizedMemoryProposal proposal) {
            payloads.add(proposal.canonicalText());
            return new MemoryProposalOutcome.Accepted(new MemoryCandidateProposal(
                    proposal.candidateId(), proposal.turn(), proposal.chartbookId(), proposal.diagramId(),
                    proposal.decisionKey(), proposal.applicabilityStage(), proposal.scope(),
                    proposal.canonicalText(), proposal.policyVersion(), proposal.declarationDigest(),
                    MemoryCandidateStatus.PENDING, 1, java.time.Instant.now().plus(proposal.ttl()),
                    java.time.Instant.now().plus(proposal.ttl()), null, null));
        }

        @Override public List<MemoryCandidateProposal> listPending(String ownerKey, String chartbookId) {
            return List.of();
        }

        @Override public MemoryMaterializeOutcome materialize(MemoryMaterializeCommand command) { return null; }
        @Override public MemoryMaterializeOutcome revoke(MemoryCandidateFence fence) { return null; }
        @Override public MemoryMaterializeOutcome delete(MemoryCandidateFence fence) { return null; }
        @Override public List<ConfirmedMemory> recall(String ownerKey, String chartbookId, int limit) {
            return List.of();
        }
    }
}
