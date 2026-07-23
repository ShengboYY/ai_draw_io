package org.zipp.ai.domain.multimodal;

import java.util.List;
import java.util.Map;

/** A candidate exists only for Converted; unsafe topology stops with a typed outcome. */
public sealed interface ImageToDiagramOutcome {
    record Converted(String mxGraphModelXml, List<String> cellIds,
                     ObservedDiagramGraph graph) implements ImageToDiagramOutcome {
        public Converted {
            if (mxGraphModelXml == null || mxGraphModelXml.isBlank()) {
                throw new IllegalArgumentException("mxGraphModelXml is required");
            }
            cellIds = List.copyOf(cellIds == null ? List.of() : cellIds);
        }

        public Converted(String mxGraphModelXml, List<String> cellIds) {
            this(mxGraphModelXml, cellIds, null);
        }
    }

    record NeedsConfirmation(List<String> reasons,
                             Map<String, String> observedValues) implements ImageToDiagramOutcome {
        public NeedsConfirmation {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
            observedValues = Map.copyOf(observedValues == null ? Map.of() : observedValues);
        }

        public NeedsConfirmation(List<String> reasons) {
            this(reasons, Map.of());
        }
    }

    record Rejected(List<String> reasons) implements ImageToDiagramOutcome {
        public Rejected {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
        }
    }
}
