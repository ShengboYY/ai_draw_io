package org.zipp.ai.domain.grounding;

import org.zipp.ai.domain.citation.model.valobj.CitationBinding;
import org.zipp.ai.domain.multimodal.DirectSourceOutcome;
import org.zipp.ai.domain.retrieval.EvidenceBundle;
import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import org.zipp.ai.domain.retrieval.EvidenceOrigin;
import org.zipp.ai.domain.retrieval.PreparedEvidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministically joins direct-image and retrieved evidence without composing canvas semantics. */
public final class DirectAndRetrievalEvidenceComposer {

    public Outcome compose(DirectSourceOutcome.Prepared direct, PreparedEvidence retrieval) {
        Objects.requireNonNull(direct, "direct");
        Objects.requireNonNull(retrieval, "retrieval");
        EvidenceBundle retrievedBundle = retrieval.bundle();
        List<String> conflicts = conflicts(direct, retrievedBundle);
        if (!conflicts.isEmpty()) return new Outcome.Conflict(conflicts);

        List<EvidenceBundleItem> combined = new ArrayList<>(direct.evidenceAccess().items());
        combined.addAll(retrievedBundle.items());
        EvidenceBundle bundle = new EvidenceBundle(
                "composed-" + retrievedBundle.bundleId(),
                retrievedBundle.requestId(),
                retrievedBundle.runId(),
                retrievedBundle.effectiveSourceMode(),
                combined);
        // Supplemental elements must always cite retrieved evidence; AI knowledge is disabled.
        EvidenceAccessContext access = EvidenceAccessContext.from(bundle, false);
        return new Outcome.Ready(
                direct.mxGraphModelXml(),
                Set.copyOf(direct.cellIds()),
                bundle,
                access,
                direct.citationBindings());
    }

    private List<String> conflicts(DirectSourceOutcome.Prepared direct,
                                   EvidenceBundle retrieval) {
        LinkedHashSet<String> conflicts = new LinkedHashSet<>();
        if (!Objects.equals(direct.evidenceAccess().runId(), retrieval.runId())) {
            conflicts.add("RUN_ID_MISMATCH");
        }
        if (direct.evidenceAccess().items().isEmpty()
                || direct.evidenceAccess().items().stream()
                .anyMatch(item -> item.origin() != EvidenceOrigin.DIRECT_ATTACHMENT)) {
            conflicts.add("INVALID_DIRECT_ORIGIN");
        }
        if (retrieval.items().isEmpty()) {
            conflicts.add("NO_RETRIEVED_EVIDENCE");
        }
        if (retrieval.items().stream()
                .anyMatch(item -> item.origin() == EvidenceOrigin.DIRECT_ATTACHMENT)) {
            conflicts.add("RETRIEVAL_CONTAINS_DIRECT_ORIGIN");
        }
        Set<String> directKeys = direct.evidenceAccess().allowedCitationKeys();
        if (retrieval.items().stream().map(EvidenceBundleItem::citationKey)
                .anyMatch(directKeys::contains)) {
            conflicts.add("CITATION_KEY_COLLISION");
        }
        Set<String> boundCells = direct.citationBindings().stream()
                .map(CitationBinding::cellId)
                .collect(java.util.stream.Collectors.toSet());
        if (!boundCells.equals(Set.copyOf(direct.cellIds()))) {
            conflicts.add("DIRECT_BINDING_MISMATCH");
        }
        return List.copyOf(conflicts);
    }

    public sealed interface Outcome {
        record Ready(String baseCanvasXml,
                     Set<String> immutableDirectCellIds,
                     EvidenceBundle combinedBundle,
                     EvidenceAccessContext evidenceAccess,
                     List<CitationBinding> directBindings) implements Outcome {
            public Ready {
                if (baseCanvasXml == null || baseCanvasXml.isBlank()) {
                    throw new IllegalArgumentException("baseCanvasXml is required");
                }
                immutableDirectCellIds = Set.copyOf(
                        immutableDirectCellIds == null ? Set.of() : immutableDirectCellIds);
                Objects.requireNonNull(combinedBundle, "combinedBundle");
                Objects.requireNonNull(evidenceAccess, "evidenceAccess");
                directBindings = List.copyOf(directBindings == null ? List.of() : directBindings);
            }
        }

        record Conflict(List<String> reasons) implements Outcome {
            public Conflict {
                reasons = List.copyOf(reasons == null ? List.of() : reasons);
            }
        }
    }
}
