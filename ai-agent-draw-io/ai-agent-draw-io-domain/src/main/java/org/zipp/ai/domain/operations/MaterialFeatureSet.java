package org.zipp.ai.domain.operations;

/** Operator-controlled rollout stages. Anonymous upload is intentionally represented last. */
public record MaterialFeatureSet(boolean ingestionEnabled,
                                 boolean uploadEnabled,
                                 boolean libraryEnabled,
                                 boolean lifecycleEnabled,
                                 boolean retrievalEnabled,
                                 boolean retrievalShadowEnabled,
                                 boolean citationCommitEnabled,
                                 boolean evidenceAnswerEnabled,
                                 boolean anonymousUploadEnabled) {
    public static MaterialFeatureSet allDisabled() {
        return new MaterialFeatureSet(false, false, false, false, false, false, false, false, false);
    }

    public static MaterialFeatureSet allEnabled() {
        return new MaterialFeatureSet(true, true, true, true, true, false, true, true, true);
    }
}
