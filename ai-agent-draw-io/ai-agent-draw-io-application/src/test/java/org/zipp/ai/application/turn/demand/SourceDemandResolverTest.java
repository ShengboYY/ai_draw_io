package org.zipp.ai.application.turn.demand;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SourceDemandResolverTest {

    private final SourceDemandResolver resolver = new SourceDemandResolver();
    private final DemandResolutionPolicy policy = DemandResolutionPolicy.m2Default();

    @Test
    void noSourceProposalBecomesPlainWithoutAnySourceInput() {
        RestrictedSourceDemandInput input = input("draw a login flow", List.of(), Optional.empty());
        SourceDemandResolution result = resolver.resolve(
                new NoSourceDemandProposal(evidence(input), "普通画图"), input, policy);

        ResolvedSourceDemand resolved = assertInstanceOf(ResolvedSourceDemand.class, result);
        assertInstanceOf(NoSourceDemand.class, resolved.decision());
    }

    @Test
    void currentAttachmentDemandRequiresTheSameMessageBinding() {
        RestrictedSourceDemandInput input = input(
                "use this attachment", List.of(new OpaqueConversationFileRef("file-1")), Optional.empty());
        SourceDemandProposal proposal = new TypedSourceDemandProposal(
                SourceDemandKind.CURRENT_MESSAGE_ATTACHMENTS_REQUIRED,
                List.of("file-1"), null, evidence(input), "使用当前附件");

        ResolvedSourceDemand resolved = assertInstanceOf(
                ResolvedSourceDemand.class, resolver.resolve(proposal, input, policy));
        AcceptedSourceDemand demand = assertInstanceOf(AcceptedSourceDemand.class, resolved.decision());
        assertEquals(List.of("file-1"), demand.attachmentRefs());

        SourceDemandResolution unbound = resolver.resolve(
                new TypedSourceDemandProposal(
                        SourceDemandKind.CURRENT_MESSAGE_ATTACHMENTS_REQUIRED,
                        List.of("conversation-file-not-bound"), null, evidence(input), "使用附件"),
                input,
                policy);
        NeedsSourceClarification clarification = assertInstanceOf(
                NeedsSourceClarification.class, unbound);
        assertEquals("ATTACHMENT_REFERENT", clarification.kind());
    }

    @Test
    void optionalDiscoveryWithoutChartbookUsesSignedPlainFallback() {
        RestrictedSourceDemandInput input = input("draw a login flow", List.of(), Optional.empty());
        SourceDemandResolution result = resolver.resolve(
                new TypedSourceDemandProposal(
                        SourceDemandKind.OPTIONAL_DISCOVERY,
                        List.of(), "login flow", evidence(input), "可选检查相关资料"),
                input,
                policy);

        ResolvedSourceDemand resolved = assertInstanceOf(ResolvedSourceDemand.class, result);
        assertInstanceOf(NoSourceDemand.class, resolved.decision());
        assertEquals(true, resolved.reasons().stream().anyMatch(reason ->
                reason.code() == DemandResolutionCode.OPTIONAL_FALLBACK_SIGNED));
    }

    @Test
    void evidenceFromAnotherInstructionCannotBecomePlainOrSourceDemand() {
        RestrictedSourceDemandInput input = input("draw a login flow", List.of(), Optional.empty());
        CurrentInstruction other = new CurrentInstruction("use the specification");
        ProposalEvidence forged = new ProposalEvidence(
                List.of(new CurrentInstructionSpan(0, other.value().length(), other.spanDigest(0, other.value().length()))),
                Confidence.HIGH,
                Optional.empty());

        SourceDemandUnavailable unavailable = assertInstanceOf(
                SourceDemandUnavailable.class,
                resolver.resolve(new NoSourceDemandProposal(forged, "plain"), input, policy));
        assertEquals("SOURCE_DEMAND_EVIDENCE_INVALID", unavailable.code());
    }

    private RestrictedSourceDemandInput input(
            String instruction,
            List<OpaqueConversationFileRef> attachments,
            Optional<String> membership
    ) {
        return new RestrictedSourceDemandInput(
                new CurrentInstruction(instruction), attachments, membership, Set.of());
    }

    private ProposalEvidence evidence(RestrictedSourceDemandInput input) {
        CurrentInstruction instruction = input.instruction();
        return new ProposalEvidence(
                List.of(new CurrentInstructionSpan(
                        0, instruction.value().length(), instruction.spanDigest(0, instruction.value().length()))),
                Confidence.HIGH,
                Optional.empty());
    }
}
