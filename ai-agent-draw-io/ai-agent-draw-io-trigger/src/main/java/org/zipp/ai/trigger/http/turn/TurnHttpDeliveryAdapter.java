package org.zipp.ai.trigger.http.turn;

import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.TurnDeliveryExecutor;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;

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
        TurnSubmission submission = executeCanonical(actor, request, sink);
        return new TurnHttpDeliveryResult(submission, sink.events(), sink.isDetached());
    }

    /**
     * Executes the same delivery boundary without subscribing the HTTP response to progress.
     * The V2 stream endpoint only promises the durable submission disposition; accepted work
     * continues through the server-owned runner and is observed through status.
     */
    public TurnSubmission executeSubmission(AuthenticatedActor actor, TurnHttpRequest request) {
        return executeCanonical(actor, request, ignored -> { });
    }

    public TurnHttpDeliveryResult executeLegacySync(AuthenticatedActor actor, ChatRequestDTO request) {
        BufferingTurnEventSink sink = new BufferingTurnEventSink();
        TurnSubmission submission = executor.execute(actor, translator.translateLegacy(request), sink);
        return new TurnHttpDeliveryResult(submission, sink.events(), sink.isDetached());
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
