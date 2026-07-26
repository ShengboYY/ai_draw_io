package org.zipp.ai.trigger.http.turn;

import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.application.turn.ClarificationId;
import org.zipp.ai.application.turn.ConversationReferenceResolver;
import org.zipp.ai.application.turn.ExplicitMemoryDecision;
import org.zipp.ai.application.turn.NoClarificationReply;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.ReplyToClarification;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.UntrustedLegacyVersionDeclaration;
import org.zipp.ai.application.turn.UserTurnCommand;

import java.util.List;
import java.util.Objects;

/**
 * Compatibility-only HTTP mapper. It maps fields and never resolves owners, files, or sources.
 */
public final class TurnHttpRequestTranslator {

    public UserTurnCommand translate(TurnHttpRequest request) {
        Objects.requireNonNull(request, "request");
        return new UserTurnCommand(
                required(request.turnId(), "turnId"),
                required(request.conversationReference(), "conversationReference"),
                required(request.diagramId(), "diagramId"),
                required(request.clientMessageId(), "clientMessageId"),
                required(request.content(), "content"),
                request.runtimeSessionId(),
                declarations(request.currentTurnAttachmentRefs(), request.clarificationId(),
                        request.legacySelectedSourceIds(), request.content(), null));
    }

    /**
     * Maps the legacy ChatRequestDTO without trusting its userId, canvas, history, or source
     * fields. Selected library versions remain explicitly untrusted compatibility declarations.
     */
    public UserTurnCommand translateLegacy(ChatRequestDTO request) {
        Objects.requireNonNull(request, "request");
        String sessionId = request.getSessionId();
        String conversationReference = blank(sessionId)
                ? ConversationReferenceResolver.DEFAULT_REFERENCE
                : canonicalizeLegacyReference(sessionId);
        String turnId = firstPresent(request.getRequestId(), request.getRunId(),
                request.getResponseMessageId());
        String clientMessageId = firstPresent(request.getResponseMessageId(), request.getRequestId(), turnId);
        return new UserTurnCommand(
                required(turnId, "turnId"),
                conversationReference,
                required(request.getDiagramId(), "diagramId"),
                required(clientMessageId, "clientMessageId"),
                required(request.getMessage(), "content"),
                sessionId,
                declarations(request.getCurrentTurnAttachmentRefs(), null,
                        request.getSelectedLibraryVersionIds(), request.getMessage(), request.getMemoryChartbookId()));
    }

    private TurnDeclarations declarations(
            List<String> attachmentRefs,
            String clarificationId,
            List<String> legacySources,
            String requestContent,
            String memoryChartbookId
    ) {
        List<OpaqueConversationFileRef> attachments = attachmentRefs == null
                ? List.of()
                : attachmentRefs.stream()
                .map(value -> new OpaqueConversationFileRef(required(value, "attachmentRef")))
                .toList();
        List<UntrustedLegacyVersionDeclaration> sources = legacySources == null
                ? List.of()
                : legacySources.stream()
                .map(value -> new UntrustedLegacyVersionDeclaration(required(value, "legacySource")))
                .toList();
        return new TurnDeclarations(
                attachments,
                blank(clarificationId)
                        ? new NoClarificationReply()
                        : new ReplyToClarification(new ClarificationId(clarificationId)),
                sources,
                ExplicitMemoryDecision.fromUserContent(requestContent, memoryChartbookId)
                        .<org.zipp.ai.application.turn.MemoryWriteDeclaration>map(
                                ExplicitMemoryDecision::declaration)
                        .orElseGet(org.zipp.ai.application.turn.NoMemoryWrite::new));
    }

    private String canonicalizeLegacyReference(String sessionId) {
        // Legacy DTOs have no typed reference contract; every sessionId stays in the alias
        // namespace, even when its opaque value happens to use a reserved prefix.
        return "legacy:" + sessionId;
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (!blank(value)) {
                return value;
            }
        }
        return null;
    }

    private String required(String value, String field) {
        if (blank(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
