package org.zipp.ai.application.turn.planning;

import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.classification.SemanticIntent;
import org.zipp.ai.application.turn.demand.AcceptedSourceDemand;
import org.zipp.ai.application.turn.demand.DemandResolutionReason;
import org.zipp.ai.application.turn.demand.NeedsSourceClarification;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;

import java.time.Duration;
import java.util.List;

public sealed interface PrePlanOutcome
        permits PrePlanOutcome.SourceFreeReady,
        PrePlanOutcome.SourcePlanningRequired,
        PrePlanOutcome.NeedsClarification,
        PrePlanOutcome.Unsupported,
        PrePlanOutcome.Unavailable {

    record SourceFreeReady(
            PlainDrawPlan plan,
            PlanningLineageFingerprint lineage,
            String contextReadSetDigest,
            String inputBindingDigest
    ) implements PrePlanOutcome {
        public SourceFreeReady {
            if (plan == null || lineage == null) {
                throw new IllegalArgumentException("source-free plan values must not be null");
            }
            PlanningContractValues.digest(contextReadSetDigest, "contextReadSetDigest");
            PlanningContractValues.digest(inputBindingDigest, "inputBindingDigest");
        }
    }

    record SourcePlanningRequired(
            SemanticIntent intent,
            ResolvedSourceDemand demand,
            AcceptedSourceDemand accepted,
            PlanningLineageFingerprint lineage,
            String contextReadSetDigest,
            String inputBindingDigest
    ) implements PrePlanOutcome {
        public SourcePlanningRequired {
            if (intent == null || demand == null || accepted == null || lineage == null) {
                throw new IllegalArgumentException("source planning values must not be null");
            }
            if (!demand.decision().equals(accepted)) {
                throw new IllegalArgumentException("accepted source demand must be the resolved decision");
            }
            PlanningContractValues.digest(contextReadSetDigest, "contextReadSetDigest");
            PlanningContractValues.digest(inputBindingDigest, "inputBindingDigest");
        }
    }

    record NeedsClarification(
            NeedsSourceClarification clarification,
            PlanningLineageFingerprint lineage,
            String contextReadSetDigest,
            String inputBindingDigest
    ) implements PrePlanOutcome {
        public NeedsClarification {
            if (clarification == null || lineage == null) {
                throw new IllegalArgumentException("clarification values must not be null");
            }
            PlanningContractValues.digest(contextReadSetDigest, "contextReadSetDigest");
            PlanningContractValues.digest(inputBindingDigest, "inputBindingDigest");
        }
    }

    record Unsupported(
            String code,
            PlanningLineageFingerprint lineage,
            String contextReadSetDigest,
            String inputBindingDigest
    ) implements PrePlanOutcome {
        public Unsupported {
            if (code == null || code.isBlank() || lineage == null) {
                throw new IllegalArgumentException("unsupported plan values must not be blank");
            }
            PlanningContractValues.digest(contextReadSetDigest, "contextReadSetDigest");
            PlanningContractValues.digest(inputBindingDigest, "inputBindingDigest");
        }
    }

    record Unavailable(
            String code,
            Duration retryAfter,
            PlanningLineageFingerprint lineage,
            String contextReadSetDigest,
            String inputBindingDigest
    ) implements PrePlanOutcome {
        public Unavailable {
            if (code == null || code.isBlank() || retryAfter == null || retryAfter.isNegative()
                    || lineage == null) {
                throw new IllegalArgumentException("invalid pre-plan unavailable outcome");
            }
            PlanningContractValues.digest(contextReadSetDigest, "contextReadSetDigest");
            PlanningContractValues.digest(inputBindingDigest, "inputBindingDigest");
        }
    }
}
