package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.citation.model.valobj.CitationBinding;
import org.zipp.ai.domain.grounding.EvidenceAccessContext;

import java.util.List;

/** Prepared is commit-ready; confirmation and rejection never contain a canvas candidate. */
public sealed interface DirectSourceOutcome {
    record Prepared(ObservedDiagramGraph graph, String mxGraphModelXml,
                    List<String> cellIds, EvidenceAccessContext evidenceAccess,
                    List<CitationBinding> citationBindings) implements DirectSourceOutcome {
        public Prepared {
            if (graph == null) throw new IllegalArgumentException("graph is required");
            if (mxGraphModelXml == null || mxGraphModelXml.isBlank()) {
                throw new IllegalArgumentException("mxGraphModelXml is required");
            }
            cellIds = List.copyOf(cellIds == null ? List.of() : cellIds);
            if (evidenceAccess == null) throw new IllegalArgumentException("evidenceAccess is required");
            citationBindings = List.copyOf(
                    citationBindings == null ? List.of() : citationBindings);
        }
    }

    record NeedsConfirmation(List<String> reasons) implements DirectSourceOutcome {
        public NeedsConfirmation {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
        }
    }

    record Rejected(List<String> reasons) implements DirectSourceOutcome {
        public Rejected {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
        }
    }

    record Unavailable(String reason) implements DirectSourceOutcome {}

    record Cancelled() implements DirectSourceOutcome {}
}
