package org.zipp.ai.application.turn;

/** Fixed profile for the M2 tool-free Plain generation port. */
public record PlainExecutionProfile(String id) {

    public PlainExecutionProfile {
        ContractValues.requiredText(id, "id");
    }

    public static PlainExecutionProfile m2SourceFree() {
        return new PlainExecutionProfile("m2-plain-source-free");
    }
}
