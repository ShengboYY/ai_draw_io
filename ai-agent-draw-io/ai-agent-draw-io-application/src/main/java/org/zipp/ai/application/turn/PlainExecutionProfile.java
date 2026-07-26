package org.zipp.ai.application.turn;

/** Fixed profile for the M2 tool-free Plain generation port. */
public record PlainExecutionProfile(String id) {

    private static final String M2_SOURCE_FREE_ID = "m2-plain-source-free";

    public PlainExecutionProfile {
        ContractValues.requiredText(id, "id");
    }

    public static PlainExecutionProfile m2SourceFree() {
        return new PlainExecutionProfile(M2_SOURCE_FREE_ID);
    }

    /** Keeps the isolated handlers from being assembled with a mutable or legacy profile. */
    public static PlainExecutionProfile requireM2SourceFree(PlainExecutionProfile profile) {
        if (profile == null || !M2_SOURCE_FREE_ID.equals(profile.id())) {
            throw new IllegalArgumentException("PLAIN_SOURCE_FREE_PROFILE_REQUIRED");
        }
        return profile;
    }
}
