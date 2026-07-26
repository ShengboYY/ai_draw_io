package org.zipp.ai.application.turn.planning;

import java.util.List;

/** Closed post-Probe decision; Probe fallback remains distinct from a zero-source Plain route. */
public sealed interface SourcePlanDecision
        permits SourcePlanDecision.OptionalRetrievalReady,
        SourcePlanDecision.RequiredSourceReady,
        SourcePlanDecision.ProbeFallbackReady,
        SourcePlanDecision.PlanningBlocked {

    record OptionalRetrievalReady(OptionalRetrievalDrawPlan plan) implements SourcePlanDecision {
        public OptionalRetrievalReady {
            if (plan == null) {
                throw new IllegalArgumentException("Optional Retrieval plan must not be null");
            }
        }
    }

    record RequiredSourceReady(
            List<String> candidateRefs,
            PlanningLineageFingerprint lineage
    ) implements SourcePlanDecision {
        public RequiredSourceReady {
            if (candidateRefs == null || candidateRefs.isEmpty() || lineage == null) {
                throw new IllegalArgumentException("required source result must not be empty");
            }
            candidateRefs = List.copyOf(candidateRefs);
        }
    }

    record ProbeFallbackReady(
            ValidatedPlainFallback fallback,
            FallbackReason reason,
            PlanningLineageFingerprint lineage
    ) implements SourcePlanDecision {
        public ProbeFallbackReady {
            if (fallback == null || reason == null || lineage == null) {
                throw new IllegalArgumentException("Probe fallback values must not be null");
            }
        }
    }

    record PlanningBlocked(String reason, boolean retryable) implements SourcePlanDecision {
        public PlanningBlocked {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("planning block reason must not be blank");
            }
        }
    }
}
