package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.classification.RouterContextView;
import org.zipp.ai.application.turn.classification.SemanticRouterInput;

import java.util.List;

/** Projects only router-approved Base Context slices; source facts never cross this boundary. */
public final class DefaultSemanticRouterContextProjector implements SemanticRouterContextProjector {

    @Override
    public SemanticRouterInput forRouter(BaseTurnContext base) {
        if (base == null) {
            throw new IllegalArgumentException("base context must not be null");
        }
        TrustedCanvasContext canvas = value(base.canvas());
        ValidatedSelectionContext selection = value(base.selection());
        ConversationContext conversation = value(base.conversation());
        ChartbookMembershipContext membership = value(base.membership());
        ChartbookProfileContext profile = value(base.chartbook());
        ConfirmedMemoryContext memory = value(base.memory());

        List<String> recentTurns = conversation == null ? List.of() : conversation.recentTurns();
        return new SemanticRouterInput(
                base.request().instruction(),
                new RouterContextView(
                        canvas != null && canvas.hasElements(),
                        canvas == null ? 0 : canvas.nodeCount(),
                        canvas == null ? 0 : canvas.edgeCount(),
                        selection != null && selection.available(),
                        recentTurns.size(),
                        profile != null,
                        memory != null,
                        canvas == null ? "" : canvas.summary(),
                        recentTurns,
                        conversation == null ? "" : conversation.summary(),
                        membership == null ? "" : membership.chartbookId(),
                        profile == null ? "" : profile.instructions(),
                        profile == null ? "" : profile.goal(),
                        profile == null ? "" : profile.summary(),
                        profile == null ? List.of() : profile.glossary(),
                        profile == null ? "" : profile.defaultStyle(),
                        profile == null ? List.of() : profile.stableConstraints(),
                        memory == null ? List.of() : memory.decisions()));
    }

    @SuppressWarnings("unchecked")
    private <T> T value(ContextRead<T> read) {
        if (read instanceof AvailableContext<?> available) {
            return (T) available.value();
        }
        if (read instanceof TruncatedContext<?> truncated) {
            return (T) truncated.value();
        }
        return null;
    }
}
