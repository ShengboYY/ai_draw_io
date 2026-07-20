package org.zipp.ai.domain.retrieval;

import java.util.List;

/** Closed outcome set. Only Ready may carry evidence resources. */
public sealed interface PreparationOutcome permits PreparationOutcome.NotRequired, PreparationOutcome.Ready,
        PreparationOutcome.Waiting, PreparationOutcome.MaterialNotReady,
        PreparationOutcome.TargetClarification, PreparationOutcome.CanvasChangedRetry,
        PreparationOutcome.StaleCanvasSelection, PreparationOutcome.CanvasUnavailable, PreparationOutcome.InsufficientEvidence,
        PreparationOutcome.Cancelled, PreparationOutcome.Failed {

    record NotRequired() implements PreparationOutcome { }
    record Ready(PreparedEvidence preparedEvidence, RetrievalDiagnostics diagnostics) implements PreparationOutcome { }
    record Waiting(List<MaterialReadiness> materialStates) implements PreparationOutcome {
        public Waiting { materialStates = List.copyOf(materialStates); }
    }
    record MaterialNotReady(List<MaterialReadiness> materialStates) implements PreparationOutcome {
        public MaterialNotReady { materialStates = List.copyOf(materialStates); }
    }
    record TargetClarification(List<TargetCandidate> candidates) implements PreparationOutcome {
        public TargetClarification { candidates = List.copyOf(candidates); }
    }
    record CanvasChangedRetry(Long expectedVersion, Long actualVersion) implements PreparationOutcome { }
    record StaleCanvasSelection(String errorCode) implements PreparationOutcome { }
    record CanvasUnavailable(String errorCode) implements PreparationOutcome { }
    record InsufficientEvidence(List<String> gaps) implements PreparationOutcome {
        public InsufficientEvidence { gaps = List.copyOf(gaps); }
    }
    record Cancelled() implements PreparationOutcome { }
    record Failed(String errorCode) implements PreparationOutcome { }
}
