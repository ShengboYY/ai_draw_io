package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.demand.DemandResolutionPolicy;
import org.zipp.ai.application.turn.demand.AmbiguousSourceDemandProposal;
import org.zipp.ai.application.turn.demand.CurrentInstructionSpan;
import org.zipp.ai.application.turn.demand.NoSourceDemandProposal;
import org.zipp.ai.application.turn.demand.ProposalEvidence;
import org.zipp.ai.application.turn.demand.RestrictedSourceDemandInput;
import org.zipp.ai.application.turn.demand.SourceDemandProposal;
import org.zipp.ai.application.turn.demand.SourceDemandResolver;
import org.zipp.ai.application.turn.demand.SourceDemandUnavailable;
import org.zipp.ai.application.turn.demand.TypedSourceDemandProposal;

import java.util.List;
import java.util.Objects;

/**
 * Runs one semantic model call and applies deterministic source policy to its untrusted result.
 */
public final class TurnClassificationService {

    private final SemanticIntentRouterPort router;
    private final SourceDemandResolver demandResolver;
    private final DemandResolutionPolicy demandPolicy;

    public TurnClassificationService(
            SemanticIntentRouterPort router,
            SourceDemandResolver demandResolver,
            DemandResolutionPolicy demandPolicy
    ) {
        this.router = Objects.requireNonNull(router, "router");
        this.demandResolver = Objects.requireNonNull(demandResolver, "demandResolver");
        this.demandPolicy = Objects.requireNonNull(demandPolicy, "demandPolicy");
    }

    public TurnClassificationOutcome classify(
            SemanticRouterInput routerInput,
            RestrictedSourceDemandInput demandInput
    ) {
        Objects.requireNonNull(routerInput, "routerInput");
        Objects.requireNonNull(demandInput, "demandInput");
        if (!routerInput.instruction().digest().equals(demandInput.instruction().digest())) {
            return new TurnClassificationUnavailable("CLASSIFICATION_INPUT_DIGEST_MISMATCH");
        }
        if (!routerInput.modelInputBinding().isBound()
                || !routerInput.eligibleAttachmentRefs()
                .equals(demandInput.eligibleAttachmentRefs())
                || !routerInput.attachmentCandidates().equals(demandInput.attachmentCandidates())
                || !routerInput.chartbookMembership().equals(demandInput.chartbookMembership())
                || !routerInput.attachmentBindingDigest()
                .equals(demandInput.attachmentBindingDigest())) {
            return new TurnClassificationUnavailable("CLASSIFICATION_MODEL_INPUT_BINDING_INVALID");
        }

        SemanticIntentOutcome intentOutcome;
        try {
            intentOutcome = router.route(routerInput);
        } catch (RuntimeException exception) {
            return new TurnClassificationUnavailable("V2_SEMANTIC_ROUTER_UNAVAILABLE");
        }
        if (intentOutcome instanceof SemanticIntentUnavailable unavailable) {
            return new TurnClassificationUnavailable(unavailable.code());
        }
        SemanticIntent intent = ((SemanticIntentReady) intentOutcome).intent();
        SourceDemandProposal proposal = proposalFrom(intent.sourceIntent(), demandInput);
        var resolution = demandResolver.resolve(proposal, demandInput, demandPolicy);
        if (resolution instanceof SourceDemandUnavailable unavailable) {
            return new TurnClassificationUnavailable(unavailable.code());
        }
        return new TurnClassificationReady(new TurnClassification(
                routerInput.instruction(), intent, proposal, resolution));
    }

    private SourceDemandProposal proposalFrom(
            SemanticSourceIntent source,
            RestrictedSourceDemandInput input
    ) {
        var instruction = input.instruction();
        var span = new CurrentInstructionSpan(
                0, instruction.value().length(),
                instruction.spanDigest(0, instruction.value().length()));
        var evidence = new ProposalEvidence(
                List.of(span),
                source.confidence(),
                java.util.Optional.ofNullable(source.relevanceQuery()),
                input.inputDigest(),
                demandPolicy.modelVersion(),
                demandPolicy.policyVersion());
        return switch (source.kind()) {
            case NO_SOURCE -> new NoSourceDemandProposal(evidence, source.safeReason());
            case AMBIGUOUS -> new AmbiguousSourceDemandProposal(evidence, source.safeReason());
            default -> new TypedSourceDemandProposal(
                    source.kind().demandKind(),
                    source.attachmentRefs(),
                    source.relevanceQuery(),
                    evidence,
                    source.safeReason());
        };
    }
}
