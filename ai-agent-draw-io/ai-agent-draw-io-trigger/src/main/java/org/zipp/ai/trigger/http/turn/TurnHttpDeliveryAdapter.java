package org.zipp.ai.trigger.http.turn;

import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.TurnDeliveryExecutor;
import org.zipp.ai.application.turn.TurnDeliveryExecution;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;

import java.util.List;
import java.util.Objects;

/**
 * HTTP-side composition: translate once, then invoke the same application executor for sync
 * buffering and NDJSON delivery.
 */
public final class TurnHttpDeliveryAdapter {

    private final TurnHttpRequestTranslator translator;
    private final TurnDeliveryExecutor executor;

    public TurnHttpDeliveryAdapter(
            TurnHttpRequestTranslator translator,
            TurnDeliveryExecutor executor
    ) {
        this.translator = Objects.requireNonNull(translator, "translator");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public TurnHttpDeliveryResult executeSync(AuthenticatedActor actor, TurnHttpRequest request) {
        BufferingTurnEventSink sink = new BufferingTurnEventSink();
        UserTurnCommand command = translator.translate(request);
        TurnDeliveryExecution execution = executor.executeTracked(actor, command, sink);
        return new TurnHttpDeliveryResult(
                execution.submission(), sink.events(), sink.isDetached(), execution.handle());
    }

    /**
     * Executes the same delivery boundary without subscribing the HTTP response to progress.
     * The V2 stream endpoint only promises the durable submission disposition; accepted work
     * continues through the server-owned runner and is observed through status.
     */
    public TurnSubmission executeSubmission(AuthenticatedActor actor, TurnHttpRequest request) {
        return executeCanonical(actor, request, ignored -> { });
    }

    public TurnHttpDeliveryResult executeProductSync(AuthenticatedActor actor, ChatRequestDTO request) {
        BufferingTurnEventSink sink = new BufferingTurnEventSink();
        UserTurnCommand command = translator.translateProductChat(request);
        TurnDeliveryExecution execution = executor.executeTracked(actor, command, sink);
        return new TurnHttpDeliveryResult(
                execution.submission(), sink.events(), sink.isDetached(), execution.handle());
    }

    /**
     * Keeps product streaming attached to the server-owned attempt while preserving the same
     * translator and execution boundary used by synchronous product delivery.
     */
    public TurnHttpDeliveryResult executeProductTracked(
            AuthenticatedActor actor,
            ChatRequestDTO request,
            TurnEventSink sink
    ) {
        Objects.requireNonNull(sink, "sink");
        UserTurnCommand command = translator.translateProductChat(request);
        TurnDeliveryExecution execution = executor.executeTracked(actor, command, sink);
        return new TurnHttpDeliveryResult(
                execution.submission(), List.of(), false, execution.handle());
    }

    public TurnSubmission executeNdjson(
            AuthenticatedActor actor,
            TurnHttpRequest request,
            NdjsonLineWriter writer
    ) {
        TurnEventSink sink = new NdjsonTurnEventSink(writer);
        return executeCanonical(actor, request, sink);
    }

    /** Keeps canonical sync and NDJSON delivery on one executor call site. */
    private TurnSubmission executeCanonical(
            AuthenticatedActor actor,
            TurnHttpRequest request,
            TurnEventSink sink
    ) {
        UserTurnCommand command = translator.translate(request);
        return executor.execute(actor, command, sink);
    }
}
