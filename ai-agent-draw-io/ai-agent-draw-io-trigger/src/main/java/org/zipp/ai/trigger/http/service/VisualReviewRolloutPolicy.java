package org.zipp.ai.trigger.http.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Centralizes stable reviewer and per-round repair cohorts so review entry points cannot drift. */
@Component
public class VisualReviewRolloutPolicy {

    private final boolean enabled;
    private final boolean autoRepairEnabled;
    private final int reviewerRolloutPercent;
    private final int roundOneRolloutPercent;
    private final int roundTwoRolloutPercent;

    // Select the configuration-backed production constructor while retaining the concise test constructor below.
    @Autowired
    public VisualReviewRolloutPolicy(
            @Value("${zipp.visual-review.enabled:false}") boolean enabled,
            @Value("${zipp.visual-review.auto-repair-enabled:false}") boolean autoRepairEnabled,
            @Value("${zipp.visual-review.rollout-percent:${ZIPP_VISUAL_REVIEW_ROLLOUT_PERCENT:100}}") int reviewerRolloutPercent,
            @Value("${zipp.visual-review.round-1-rollout-percent:${ZIPP_VISUAL_REVIEW_ROUND_1_ROLLOUT_PERCENT:100}}") int roundOneRolloutPercent,
            @Value("${zipp.visual-review.round-2-rollout-percent:${ZIPP_VISUAL_REVIEW_ROUND_2_ROLLOUT_PERCENT:100}}") int roundTwoRolloutPercent) {
        this.enabled = enabled;
        this.autoRepairEnabled = autoRepairEnabled;
        this.reviewerRolloutPercent = boundedPercent(reviewerRolloutPercent);
        this.roundOneRolloutPercent = boundedPercent(roundOneRolloutPercent);
        this.roundTwoRolloutPercent = boundedPercent(roundTwoRolloutPercent);
    }

    /** Keeps existing tests and explicit all-on/all-off wiring concise. */
    public VisualReviewRolloutPolicy(boolean enabled, boolean autoRepairEnabled) {
        this(enabled, autoRepairEnabled, 100, 100, 100);
    }

    public boolean isReviewEnabled(String ownerId, String diagramId) {
        return enabled && inCohort(ownerId, diagramId, reviewerRolloutPercent);
    }

    public boolean isAutoRepairEnabled(String ownerId, String diagramId, int repairRound) {
        if (!enabled || !autoRepairEnabled || repairRound < 1 || repairRound > 2
                || !isReviewEnabled(ownerId, diagramId)) {
            return false;
        }
        int percent = repairRound == 1 ? roundOneRolloutPercent : roundTwoRolloutPercent;
        return inCohort(ownerId, diagramId, percent);
    }

    private boolean inCohort(String ownerId, String diagramId, int percent) {
        if (ownerId == null || ownerId.isBlank() || diagramId == null || diagramId.isBlank() || percent <= 0) {
            return false;
        }
        if (percent >= 100) return true;
        return cohortBucket(ownerId + ":" + diagramId) < percent;
    }

    private int cohortBucket(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            int value = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16)
                    | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
            return Math.floorMod(value, 100);
        } catch (Exception e) {
            // SHA-256 is required by the JDK; fail closed if a broken runtime ever violates that contract.
            return 100;
        }
    }

    private int boundedPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
