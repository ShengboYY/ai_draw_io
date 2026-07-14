package org.zipp.ai.trigger.http.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Centralizes the two production rollout switches so review entry points cannot drift. */
@Component
public class VisualReviewRolloutPolicy {

    private final boolean enabled;
    private final boolean autoRepairEnabled;

    public VisualReviewRolloutPolicy(
            @Value("${zipp.visual-review.enabled:false}") boolean enabled,
            @Value("${zipp.visual-review.auto-repair-enabled:false}") boolean autoRepairEnabled) {
        this.enabled = enabled;
        this.autoRepairEnabled = autoRepairEnabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAutoRepairEnabled() {
        return enabled && autoRepairEnabled;
    }
}
