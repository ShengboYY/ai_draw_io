package org.zipp.ai.domain.retrieval;

import java.util.List;

/** Closed outcome set. Only Ready may carry evidence resources. */
public sealed interface PreparationOutcome permits PreparationOutcome.NotRequired, PreparationOutcome.Ready,
        PreparationOutcome.Waiting, PreparationOutcome.MaterialNotReady,
        PreparationOutcome.ClarificationNeeded, PreparationOutcome.CanvasChangedRetry,
        PreparationOutcome.StaleCanvasSelection, PreparationOutcome.CanvasUnavailable, PreparationOutcome.InsufficientEvidence,
        PreparationOutcome.DegradedDependency, PreparationOutcome.ShadowObserved,
        PreparationOutcome.Cancelled, PreparationOutcome.Failed {

    record NotRequired() implements PreparationOutcome { }
    record Ready(PreparedEvidence preparedEvidence, RetrievalDiagnostics diagnostics) implements PreparationOutcome { }
    record Waiting(List<MaterialReadiness> materialStates) implements PreparationOutcome {
        public Waiting { materialStates = List.copyOf(materialStates); }
    }
    record MaterialNotReady(List<MaterialReadiness> materialStates) implements PreparationOutcome {
        public MaterialNotReady { materialStates = List.copyOf(materialStates); }
    }
    record ClarificationNeeded(String reason, List<TargetCandidate> candidates) implements PreparationOutcome {
        public ClarificationNeeded {
            reason = reason == null || reason.isBlank() ? "CLARIFICATION_REQUIRED" : reason.trim();
            candidates = List.copyOf(candidates == null ? List.of() : candidates);
        }
    }
    record CanvasChangedRetry(Long expectedVersion, Long actualVersion) implements PreparationOutcome { }
    record StaleCanvasSelection(String errorCode) implements PreparationOutcome { }
    record CanvasUnavailable(String errorCode) implements PreparationOutcome { }
    record InsufficientEvidence(List<String> gaps) implements PreparationOutcome {
        public InsufficientEvidence { gaps = List.copyOf(gaps); }
    }
    record DegradedDependency(List<String> gaps) implements PreparationOutcome {
        public DegradedDependency { gaps = List.copyOf(gaps); }
    }
    record ShadowObserved(RetrievalDiagnostics diagnostics, int candidateCount) implements PreparationOutcome { }
    record Cancelled() implements PreparationOutcome { }
    record Failed(String errorCode) implements PreparationOutcome { }
}
