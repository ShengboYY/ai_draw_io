package org.zipp.ai.application.turn.context;

public record ChartbookMembershipContext(
        String chartbookId,
        long membershipRevision,
        long chartbookStatusRevision
) {

    public ChartbookMembershipContext {
        ContextValues.requiredText(chartbookId, "chartbookId");
        if (membershipRevision < 0 || chartbookStatusRevision < 0) {
            throw new IllegalArgumentException("membership revisions must not be negative");
        }
    }
}
