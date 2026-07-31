package org.zipp.ai.application.turn.context;

import java.util.Objects;

/** Server-owned context envelope; source availability, source body, snapshot, and evidence are absent. */
public record BaseTurnContext(
        CurrentRequestContext request,
        ContextRead<CurrentMessageAttachmentsContext> attachments,
        ContextRead<ActiveClarificationContext> activeClarification,
        ContextRead<TrustedCanvasContext> canvas,
        ContextRead<ValidatedSelectionContext> selection,
        ContextRead<ConversationContext> conversation,
        ContextRead<ChartbookMembershipContext> membership,
        ContextRead<ChartbookProfileContext> chartbook,
        ContextRead<AutoMemoryContext> memory,
        ContextDiagnostics diagnostics
) {

    public BaseTurnContext {
        Objects.requireNonNull(request, "request");
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
