package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.demand.DemandResolutionPolicy;
import org.zipp.ai.application.turn.demand.RestrictedSourceDemandInput;
import org.zipp.ai.application.turn.demand.SourceDemandInterpreterPort;
import org.zipp.ai.application.turn.demand.SourceDemandInterpreterUnavailable;
import org.zipp.ai.application.turn.demand.SourceDemandProposalOutcome;
import org.zipp.ai.application.turn.demand.SourceDemandProposalReady;
import org.zipp.ai.application.turn.demand.SourceDemandResolver;
import org.zipp.ai.application.turn.demand.SourceDemandUnavailable;

import java.util.Objects;

/**
 * Combines the independent router and restricted demand proposal without allowing either model
 * to bypass deterministic demand resolution.
 */
public final class TurnClassificationService {

    private final SemanticIntentRouterPort router;
    private final SourceDemandInterpreterPort demandInterpreter;
    private final SourceDemandResolver demandResolver;
    private final DemandResolutionPolicy demandPolicy;

    public TurnClassificationService(
            SemanticIntentRouterPort router,
            SourceDemandInterpreterPort demandInterpreter,
            SourceDemandResolver demandResolver,
            DemandResolutionPolicy demandPolicy
    ) {
        this.router = Objects.requireNonNull(router, "router");
        this.demandInterpreter = Objects.requireNonNull(demandInterpreter, "demandInterpreter");
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

        SemanticIntentOutcome intentOutcome = router.route(routerInput);
        if (intentOutcome instanceof SemanticIntentUnavailable unavailable) {
            return new TurnClassificationUnavailable(unavailable.code());
        }
        SemanticIntent intent = ((SemanticIntentReady) intentOutcome).intent();

        SourceDemandProposalOutcome proposalOutcome = demandInterpreter.interpret(demandInput);
        if (proposalOutcome instanceof SourceDemandInterpreterUnavailable unavailable) {
            return new TurnClassificationUnavailable(unavailable.code());
        }
        SourceDemandProposalReady ready = (SourceDemandProposalReady) proposalOutcome;
        var resolution = demandResolver.resolve(ready.proposal(), demandInput, demandPolicy);
        if (resolution instanceof SourceDemandUnavailable unavailable) {
            return new TurnClassificationUnavailable(unavailable.code());
        }
        return new TurnClassificationReady(new TurnClassification(
                routerInput.instruction(), intent, ready.proposal(), resolution));
    }
}
