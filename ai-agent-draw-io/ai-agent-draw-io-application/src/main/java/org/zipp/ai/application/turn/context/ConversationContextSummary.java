package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.ModelInputBinding;

import java.util.List;

/** Rebuilds a bounded conversation summary from the pinned message high-water. */
public final class ConversationContextSummary {

    private static final int LATEST_TURN_LIMIT = 800;
    private static final int SUMMARY_LIMIT = 2_000;

    private ConversationContextSummary() {
    }

    public static String rebuild(List<String> recentTurns, long messageHighWater) {
        List<String> turns = List.copyOf(recentTurns == null ? List.of() : recentTurns);
        String latest = turns.isEmpty() ? "" : turns.get(turns.size() - 1);
        if (latest.length() > LATEST_TURN_LIMIT) {
            latest = latest.substring(0, LATEST_TURN_LIMIT);
        }
        String summary = "messageHighWater=" + messageHighWater
                + ";recentCount=" + turns.size()
                + ";recentDigest=" + ModelInputBinding.digestOf(turns.toArray(String[]::new))
                + ";latest=" + latest;
        return summary.length() <= SUMMARY_LIMIT
                ? summary
                : summary.substring(0, SUMMARY_LIMIT);
    }
}
