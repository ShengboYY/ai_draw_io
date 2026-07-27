package org.zipp.ai.application.turn.context;

import java.util.Objects;

/**
 * Server-owned context values materialized from one exact read-set. Source availability,
 * source body, snapshots, and evidence are deliberately absent.
 */
public record ContextMaterializedSlices(
        ContextRead<CurrentMessageAttachmentsContext> attachments,
        ContextRead<ActiveClarificationContext> activeClarification,
        ContextRead<TrustedCanvasContext> canvas,
        ContextRead<ValidatedSelectionContext> selection,
        ContextRead<ConversationContext> conversation,
        ContextRead<ChartbookMembershipContext> membership,
        ContextRead<ChartbookProfileContext> chartbook,
        ContextRead<ConfirmedMemoryContext> memory,
        ContextDiagnostics diagnostics
) {

    public ContextMaterializedSlices {
        Objects.requireNonNull(attachments, "attachments");
        Objects.requireNonNull(activeClarification, "activeClarification");
        Objects.requireNonNull(canvas, "canvas");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(membership, "membership");
        Objects.requireNonNull(chartbook, "chartbook");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(diagnostics, "diagnostics");
    }
}
