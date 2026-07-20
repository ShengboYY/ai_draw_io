package org.zipp.ai.domain.grounding;

import org.zipp.ai.domain.retrieval.EvidenceBundle;
import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.*;

/** Run-scoped citation whitelist; internal identities never need to enter a model prompt. */
public final class EvidenceAccessContext {
    private final SourceMode sourceMode;
    private final String runId;
    private final boolean aiKnowledgeAllowed;
    private final Map<String, EvidenceBundleItem> items;

    private EvidenceAccessContext(SourceMode sourceMode, String runId, boolean aiKnowledgeAllowed,
                                  Map<String, EvidenceBundleItem> items) {
        this.sourceMode = sourceMode;
        this.runId = runId;
        this.aiKnowledgeAllowed = aiKnowledgeAllowed;
        this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
    }

    public static EvidenceAccessContext from(EvidenceBundle bundle, boolean aiKnowledgeAllowed) {
        Objects.requireNonNull(bundle, "bundle");
        LinkedHashMap<String, EvidenceBundleItem> indexed = new LinkedHashMap<>();
        for (EvidenceBundleItem item : bundle.items()) {
            Objects.requireNonNull(item, "bundle item");
            String key = requireText(item.citationKey(), "citationKey");
            if (indexed.putIfAbsent(key, item) != null) {
                throw new IllegalArgumentException("citation keys must be unique");
            }
        }
        boolean allowed = aiKnowledgeAllowed && bundle.effectiveSourceMode() != SourceMode.EXPLICIT_ONLY;
        return new EvidenceAccessContext(bundle.effectiveSourceMode(), requireText(bundle.runId(), "runId"), allowed, indexed);
    }

    public SourceMode sourceMode() { return sourceMode; }
    public String runId() { return runId; }
    public long runGeneration() { return 1L; }
    public boolean aiKnowledgeAllowed() { return aiKnowledgeAllowed; }
    public Set<String> allowedCitationKeys() { return items.keySet(); }
    public List<EvidenceBundleItem> items() { return List.copyOf(items.values()); }
    public Optional<EvidenceBundleItem> item(String citationKey) {
        return Optional.ofNullable(items.get(citationKey));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
