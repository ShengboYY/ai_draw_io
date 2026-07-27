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
    void availableAttachmentMetadataDoesNotForceAPlainRequestIntoSourcePlanning() {
        RestrictedSourceDemandInput input = input(
                "draw a login flow",
                List.of(new OpaqueConversationFileRef("recent-image")),
                Optional.empty());

        ResolvedSourceDemand resolved = assertInstanceOf(
                ResolvedSourceDemand.class,
                resolver.resolve(
                        new NoSourceDemandProposal(evidence(input), "普通画图"),
                        input,
                        policy));

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
    void confidenceDoesNotAddASecondConfirmationGateAfterReferentsAreBound() {
        RestrictedSourceDemandInput input = input(
                "请帮我还原一下",
                List.of(new OpaqueConversationFileRef("file-1")),
                Optional.empty());
        ProposalEvidence medium = evidence(input, Optional.empty(), Confidence.MEDIUM);

        ResolvedSourceDemand resolved = assertInstanceOf(
                ResolvedSourceDemand.class,
                resolver.resolve(
                        new TypedSourceDemandProposal(
                                SourceDemandKind.CURRENT_MESSAGE_DIRECT_REQUIRED,
                                List.of("file-1"), null, medium, "按当前图片还原"),
                        input,
                        policy));
        AcceptedSourceDemand accepted =
                assertInstanceOf(AcceptedSourceDemand.class, resolved.decision());
        assertEquals(List.of("file-1"), accepted.attachmentRefs());

        ProposalEvidence retrievalEvidence =
                evidence(input, Optional.of("总结当前附件"), Confidence.MEDIUM);
        ResolvedSourceDemand retrieval = assertInstanceOf(
                ResolvedSourceDemand.class,
                resolver.resolve(
                        new TypedSourceDemandProposal(
                                SourceDemandKind.CURRENT_MESSAGE_RETRIEVAL_REQUIRED,
                                List.of("file-1"), "总结当前附件",
                                retrievalEvidence, "检索当前附件"),
                        input,
                        policy));
        AcceptedSourceDemand acceptedRetrieval =
                assertInstanceOf(AcceptedSourceDemand.class, retrieval.decision());
        assertEquals("总结当前附件", acceptedRetrieval.relevanceQuery());

        RestrictedSourceDemandInput multipleInput = input(
                "还原这些附件",
                List.of(
                        new OpaqueConversationFileRef("file-1"),
                        new OpaqueConversationFileRef("file-2")),
                Optional.empty());
        ResolvedSourceDemand multiple = assertInstanceOf(
                ResolvedSourceDemand.class,
                resolver.resolve(
                        new TypedSourceDemandProposal(
                                SourceDemandKind.CURRENT_MESSAGE_DIRECT_REQUIRED,
                                List.of("file-1", "file-2"),
                                null,
                                evidence(multipleInput, Optional.empty(), Confidence.MEDIUM),
                                "按当前图片还原"),
                        multipleInput,
                        policy));
        assertInstanceOf(AcceptedSourceDemand.class, multiple.decision());
    }

    @Test
    void optionalDiscoveryWithoutChartbookUsesSignedPlainFallback() {
        RestrictedSourceDemandInput input = input("draw a login flow", List.of(), Optional.empty());
        ProposalEvidence evidence = evidence(input, Optional.of("login flow"));
        SourceDemandResolution result = resolver.resolve(
                new TypedSourceDemandProposal(
                        SourceDemandKind.OPTIONAL_DISCOVERY,
                        List.of(), "login flow", evidence, "可选检查相关资料"),
                input,
                policy);

        ResolvedSourceDemand resolved = assertInstanceOf(ResolvedSourceDemand.class, result);
        assertInstanceOf(NoSourceDemand.class, resolved.decision());
        assertEquals(true, resolved.reasons().stream().anyMatch(reason ->
                reason.code() == DemandResolutionCode.OPTIONAL_FALLBACK_SIGNED));
    }

    @Test
    void directAndRetrievalDemandsRemainDistinctAfterBindingValidation() {
        RestrictedSourceDemandInput input = input(
                "rebuild this image and use project facts",
                List.of(new OpaqueConversationFileRef("file-1")),
                Optional.of("chartbook-1"));
        ProposalEvidence evidence = evidence(input, Optional.of("project facts"));

        ResolvedSourceDemand resolved = assertInstanceOf(
                ResolvedSourceDemand.class,
                resolver.resolve(
                        new TypedSourceDemandProposal(
                                SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL,
                                List.of("file-1"),
                                "project facts",
                                evidence,
                                "重建附件并可选参考资料"),
                        input,
                        policy));

        AcceptedSourceDemand accepted =
                assertInstanceOf(AcceptedSourceDemand.class, resolved.decision());
        assertEquals(SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL,
                accepted.kind());
        assertEquals("project facts", accepted.relevanceQuery());
    }

    @Test
    void optionalCompositeWithoutChartbookRemainsCompositeUntilRoleAwareProbe() {
        RestrictedSourceDemandInput input = input(
                "rebuild this image and use project facts",
                List.of(new OpaqueConversationFileRef("file-1")),
                Optional.empty());
        ProposalEvidence evidence = evidence(input, Optional.of("project facts"));

        ResolvedSourceDemand resolved = assertInstanceOf(
                ResolvedSourceDemand.class,
                resolver.resolve(
                        new TypedSourceDemandProposal(
                                SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL,
                                List.of("file-1"),
                                "project facts",
                                evidence,
                                "重建附件并可选参考资料"),
                        input,
                        policy));

        AcceptedSourceDemand accepted =
                assertInstanceOf(AcceptedSourceDemand.class, resolved.decision());
        assertEquals(SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL, accepted.kind());
        assertEquals(List.of("file-1"), accepted.attachmentRefs());
    }

    @Test
    void requiredCompositeWithoutChartbookCannotDropRetrieval() {
        RestrictedSourceDemandInput input = input(
                "rebuild this image strictly using project facts",
                List.of(new OpaqueConversationFileRef("file-1")),
                Optional.empty());
        ProposalEvidence evidence = evidence(input, Optional.of("project facts"));

        NeedsSourceClarification clarification = assertInstanceOf(
                NeedsSourceClarification.class,
                resolver.resolve(
                        new TypedSourceDemandProposal(
                                SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_REQUIRED,
                                List.of("file-1"),
                                "project facts",
                                evidence,
                                "必须使用附件和项目资料"),
                        input,
                        policy));

        assertEquals("COMPOSITE_RETRIEVAL_SCOPE", clarification.kind());
    }

    @Test
    void evidenceFromAnotherInstructionCannotBecomePlainOrSourceDemand() {
        RestrictedSourceDemandInput input = input("draw a login flow", List.of(), Optional.empty());
        CurrentInstruction other = new CurrentInstruction("use the specification");
        ProposalEvidence forged = new ProposalEvidence(
                List.of(new CurrentInstructionSpan(0, other.value().length(), other.spanDigest(0, other.value().length()))),
                Confidence.HIGH,
                Optional.empty(), input.inputDigest(), policy.modelVersion(), policy.policyVersion());

        SourceDemandUnavailable unavailable = assertInstanceOf(
                SourceDemandUnavailable.class,
                resolver.resolve(new NoSourceDemandProposal(forged, "plain"), input, policy));
        assertEquals("SOURCE_DEMAND_EVIDENCE_INVALID", unavailable.code());
    }

    @Test
    void staleInputDigestCannotBecomeAResolvedDemand() {
        RestrictedSourceDemandInput input = input("draw a login flow", List.of(), Optional.empty());
        ProposalEvidence stale = new ProposalEvidence(
                evidence(input).spans(), Confidence.HIGH, Optional.empty(),
                "f".repeat(64), policy.modelVersion(), policy.policyVersion());

        SourceDemandUnavailable unavailable = assertInstanceOf(SourceDemandUnavailable.class,
                resolver.resolve(new NoSourceDemandProposal(stale, "plain"), input, policy));
        assertEquals("SOURCE_DEMAND_INPUT_DIGEST_INVALID", unavailable.code());
    }

    @Test
    void proposalFromAnotherModelOrPolicyVersionCannotBeResolved() {
        RestrictedSourceDemandInput input = input("draw a login flow", List.of(), Optional.empty());
        ProposalEvidence stale = new ProposalEvidence(
                evidence(input).spans(), Confidence.HIGH, Optional.empty(), input.inputDigest(),
                "old-model", policy.policyVersion());

        SourceDemandUnavailable unavailable = assertInstanceOf(SourceDemandUnavailable.class,
                resolver.resolve(new NoSourceDemandProposal(stale, "plain"), input, policy));
        assertEquals("SOURCE_DEMAND_POLICY_VERSION_INVALID", unavailable.code());
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
        return evidence(input, Optional.empty());
    }

    private ProposalEvidence evidence(
            RestrictedSourceDemandInput input,
            Optional<String> relevanceQuery
    ) {
        return evidence(input, relevanceQuery, Confidence.HIGH);
    }

    private ProposalEvidence evidence(
            RestrictedSourceDemandInput input,
            Optional<String> relevanceQuery,
            Confidence confidence
    ) {
        CurrentInstruction instruction = input.instruction();
        return new ProposalEvidence(
                List.of(new CurrentInstructionSpan(
                        0, instruction.value().length(), instruction.spanDigest(0, instruction.value().length()))),
                confidence,
                relevanceQuery, input.inputDigest(), policy.modelVersion(), policy.policyVersion());
    }
}
