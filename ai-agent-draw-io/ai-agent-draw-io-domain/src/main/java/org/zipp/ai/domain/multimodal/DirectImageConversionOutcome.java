package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateSaveResult;

import java.util.List;
import java.util.Map;

/** Only Committed exposes a persisted canvas; all other outcomes are non-mutating. */
public sealed interface DirectImageConversionOutcome {
    record Committed(String canvasXml,
                     CanvasStateSaveResult saveResult) implements DirectImageConversionOutcome {
        public Committed {
            if (canvasXml == null || canvasXml.isBlank()) {
                throw new IllegalArgumentException("canvasXml is required");
            }
            if (saveResult == null) throw new IllegalArgumentException("saveResult is required");
        }
    }

    record NeedsConfirmation(List<String> reasons,
                             Map<String, String> observedValues) implements DirectImageConversionOutcome {
        public NeedsConfirmation {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
            observedValues = Map.copyOf(observedValues == null ? Map.of() : observedValues);
        }

        public NeedsConfirmation(List<String> reasons) {
            this(reasons, Map.of());
        }
    }

    record Rejected(List<String> reasons) implements DirectImageConversionOutcome {
        public Rejected {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
        }
    }

    record Unavailable(String reason) implements DirectImageConversionOutcome {}

    record Cancelled() implements DirectImageConversionOutcome {}
}
