package org.zipp.ai.application.turn;

/** Authentication-derived owner identity; request-body owner fields are excluded. */
public record AuthenticatedActor(String ownerKey, String cohortKey) {

    public AuthenticatedActor {
        ContractValues.requiredText(ownerKey, "ownerKey");
        ContractValues.requiredText(cohortKey, "cohortKey");
    }
}
