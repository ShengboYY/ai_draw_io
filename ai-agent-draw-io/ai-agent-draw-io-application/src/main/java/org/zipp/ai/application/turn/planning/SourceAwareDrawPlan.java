package org.zipp.ai.application.turn.planning;

import java.util.List;
import java.util.Objects;

/** Planner-issued source-aware graph; constructors remain package-private. */
public sealed interface SourceAwareDrawPlan
        permits SourceAwareDrawPlan.Direct,
        SourceAwareDrawPlan.OptionalComposite,
        SourceAwareDrawPlan.RequiredComposite {

    final class Direct implements SourceAwareDrawPlan {

        private final DirectSelector direct;

        Direct(DirectSelector direct) {
            this.direct = Objects.requireNonNull(direct, "direct");
        }

        public DirectSelector direct() {
            return direct;
        }
    }

    final class OptionalComposite implements SourceAwareDrawPlan {

        private final DirectSelector direct;
        private final List<RetrievalCandidateFact> retrieval;
        private final DirectSourceReusePolicy reusePolicy;
        private final ValidatedDirectOnlyFallback validatedDirectOnlyFallback;

        OptionalComposite(
                DirectSelector direct,
                List<RetrievalCandidateFact> retrieval,
                DirectSourceReusePolicy reusePolicy,
                ValidatedDirectOnlyFallback validatedDirectOnlyFallback
        ) {
            this.direct = Objects.requireNonNull(direct, "direct");
            this.retrieval = List.copyOf(retrieval == null ? List.of() : retrieval);
            this.reusePolicy = Objects.requireNonNull(reusePolicy, "reusePolicy");
            this.validatedDirectOnlyFallback = Objects.requireNonNull(
                    validatedDirectOnlyFallback, "validatedDirectOnlyFallback");
        }

        public DirectSelector direct() {
            return direct;
        }

        public List<RetrievalCandidateFact> retrieval() {
            return retrieval;
        }

        public DirectSourceReusePolicy reusePolicy() {
            return reusePolicy;
        }

        public ValidatedDirectOnlyFallback validatedDirectOnlyFallback() {
            return validatedDirectOnlyFallback;
        }
    }

    final class RequiredComposite implements SourceAwareDrawPlan {

        private final DirectSelector direct;
        private final List<RetrievalCandidateFact> retrieval;
        private final DirectSourceReusePolicy reusePolicy;

        RequiredComposite(
                DirectSelector direct,
                List<RetrievalCandidateFact> retrieval,
                DirectSourceReusePolicy reusePolicy
        ) {
            if (retrieval == null || retrieval.isEmpty()) {
                throw new IllegalArgumentException("Required Composite retrieval must not be empty");
            }
            this.direct = Objects.requireNonNull(direct, "direct");
            this.retrieval = List.copyOf(retrieval);
            this.reusePolicy = Objects.requireNonNull(reusePolicy, "reusePolicy");
        }

        public DirectSelector direct() {
            return direct;
        }

        public List<RetrievalCandidateFact> retrieval() {
            return retrieval;
        }

        public DirectSourceReusePolicy reusePolicy() {
            return reusePolicy;
        }
    }
}
