package org.zipp.ai.trigger.http.turn;

import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.CancelTurnCommand;
import org.zipp.ai.application.turn.CancelTurnOutcome;
import org.zipp.ai.application.turn.ConversationCatalogPort;
import org.zipp.ai.application.turn.ConversationReferenceResolver;
import org.zipp.ai.application.turn.ConversationRef;
import org.zipp.ai.application.turn.TurnControlFacade;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatusQuery;
import org.zipp.ai.application.turn.TurnStatusQueryOutcome;

import java.util.Objects;

/**
 * Maps HTTP control references to the canonical, owner-fenced application control facade.
 *
 * <p>This adapter deliberately exposes application outcomes instead of inventing HTTP status
 * policy; a future controller can map the sealed outcomes without bypassing canonical identity.
 */
public final class TurnHttpControlAdapter {

    private final ConversationCatalogPort conversations;
    private final ConversationReferenceResolver conversationResolver;
    private final TurnControlFacade control;
    private final TurnHttpControlMapper mapper;

    public TurnHttpControlAdapter(
            ConversationCatalogPort conversations,
            ConversationReferenceResolver conversationResolver,
            TurnControlFacade control
    ) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.conversationResolver = Objects.requireNonNull(conversationResolver, "conversationResolver");
        this.control = Objects.requireNonNull(control, "control");
        this.mapper = new TurnHttpControlMapper();
    }

    public TurnStatusQueryOutcome status(
            AuthenticatedActor actor,
            TurnHttpControlRequest request
    ) {
        TurnKey key = key(actor, request);
        return control.status(actor, new TurnStatusQuery(key));
    }

    /** Maps the same canonical status query to a future HTTP response without re-resolving it. */
    public TurnHttpStatusResult statusResponse(
            AuthenticatedActor actor,
            TurnHttpControlRequest request
    ) {
        return mapper.mapStatus(status(actor, request));
    }

    public CancelTurnOutcome cancel(
            AuthenticatedActor actor,
            TurnHttpCancelRequest request
    ) {
        TurnKey key = key(actor, request.target());
        return control.cancel(actor, new CancelTurnCommand(key, request.reason()));
    }

    /** Maps the durable cancellation outcome while preserving its idempotent application payload. */
    public TurnHttpCancelResult cancelResponse(
            AuthenticatedActor actor,
            TurnHttpCancelRequest request
    ) {
        return mapper.mapCancel(cancel(actor, request));
    }

    private TurnKey key(AuthenticatedActor actor, TurnHttpControlRequest request) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        ConversationRef conversation = conversationResolver.resolve(
                conversations, actor, request.conversationReference(), request.diagramId());
        return TurnKey.of(actor, conversation, request.turnId());
    }
}
