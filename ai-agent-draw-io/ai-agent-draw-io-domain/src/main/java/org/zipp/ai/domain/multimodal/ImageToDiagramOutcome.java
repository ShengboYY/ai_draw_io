package org.zipp.ai.domain.multimodal;

import java.util.List;

public sealed interface ImageToDiagramOutcome {
    record Converted(String mxGraphModelXml, List<String> cellIds) implements ImageToDiagramOutcome {
        public Converted {
            if (mxGraphModelXml == null || mxGraphModelXml.isBlank()) {
                throw new IllegalArgumentException("mxGraphModelXml is required");
            }
            cellIds = List.copyOf(cellIds == null ? List.of() : cellIds);
        }
    }

    record NeedsConfirmation(List<String> reasons) implements ImageToDiagramOutcome {
        public NeedsConfirmation {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
        }
    }

    record Rejected(List<String> reasons) implements ImageToDiagramOutcome {
        public Rejected {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
        }
    }
}
