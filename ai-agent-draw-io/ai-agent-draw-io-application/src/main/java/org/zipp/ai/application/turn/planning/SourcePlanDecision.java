package org.zipp.ai.application.turn.planning;

import java.util.List;

/** Closed post-Probe decision; Probe fallback remains distinct from a zero-source Plain route. */
public sealed interface SourcePlanDecision
        permits SourcePlanDecision.OptionalRetrievalReady,
        SourcePlanDecision.RequiredSourceReady,
        SourcePlanDecision.SourceReady,
        SourcePlanDecision.DirectOnlyReady,
        SourcePlanDecision.ProbeFallbackReady,
        SourcePlanDecision.NeedClarification,
        SourcePlanDecision.PlanningBlocked {

    record OptionalRetrievalReady(OptionalRetrievalDrawPlan plan) implements SourcePlanDecision {
        public OptionalRetrievalReady {
            if (plan == null) {
                throw new IllegalArgumentException("Optional Retrieval plan must not be null");
            }
        }
    }

    record SourceReady(BoundSourcePlan bound) implements SourcePlanDecision {
        public SourceReady {
            if (bound == null) {
                throw new IllegalArgumentException("bound source plan must not be null");
            }
        }
    }

    record DirectOnlyReady(BoundSourcePlan bound) implements SourcePlanDecision {
        public DirectOnlyReady {
            if (bound == null
                    || !(bound.plan() instanceof SourceAwareDrawPlan.OptionalComposite)
                    || !(bound.entry() instanceof SourceExecutionEntry.SignedDirectOnly)) {
                throw new IllegalArgumentException("invalid Direct-only plan");
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

    record NeedClarification(
            String code,
            List<String> opaqueCandidateRefs
    ) implements SourcePlanDecision {
        public NeedClarification {
            if (code == null || code.isBlank()
                    || opaqueCandidateRefs == null || opaqueCandidateRefs.isEmpty()) {
                throw new IllegalArgumentException("clarification values must not be empty");
            }
            opaqueCandidateRefs = List.copyOf(opaqueCandidateRefs);
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
