package org.zipp.ai.domain.multimodal;

import java.util.List;

public sealed interface VisualObservationOutcome {
    record Verified(List<VerifiedObservation> observations) implements VisualObservationOutcome {
        public Verified {
            observations = List.copyOf(observations == null ? List.of() : observations);
            if (observations.isEmpty()) throw new IllegalArgumentException("verified observations are required");
        }
    }
    record DiagramVerified(ObservedDiagramGraph graph) implements VisualObservationOutcome {
        public DiagramVerified {
            if (graph == null) throw new IllegalArgumentException("verified diagram graph is required");
        }
    }
    record Gap(List<String> reasons) implements VisualObservationOutcome {
        public Gap { reasons = List.copyOf(reasons == null ? List.of() : reasons); }
    }
    record Rejected(String reason) implements VisualObservationOutcome {}
    record Unavailable(String reason) implements VisualObservationOutcome {}
    record Cancelled() implements VisualObservationOutcome {}
}
