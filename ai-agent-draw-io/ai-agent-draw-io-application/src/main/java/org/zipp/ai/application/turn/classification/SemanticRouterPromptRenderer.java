package org.zipp.ai.application.turn.classification;

import java.util.List;

/** Renders the router projection; every value is labelled as data and length bounded upstream. */
public final class SemanticRouterPromptRenderer {

    public String render(SemanticRouterInput input) {
        if (input == null) {
            throw new IllegalArgumentException("semantic router input must not be null");
        }
        RouterContextView context = input.context();
        StringBuilder prompt = new StringBuilder(2_000);
        append(prompt, "CURRENT_REQUEST_DATA", input.instruction().value());
        append(prompt, "CANVAS_FACTS_DATA", "available=" + context.canvasAvailable()
                + ";nodeCount=" + context.canvasNodeCount()
                + ";edgeCount=" + context.canvasEdgeCount()
                + ";summary=" + context.canvasSummary());
        append(prompt, "SELECTION_FACTS_DATA", "available=" + context.selectionAvailable());
        append(prompt, "CONVERSATION_DATA", "recentMessageCount=" + context.recentMessageCount()
                + ";recentTurns=" + join(context.recentTurns())
                + ";summary=" + context.conversationSummary());
        append(prompt, "CHARTBOOK_MEMBERSHIP_DATA", context.chartbookMembership());
        append(prompt, "CHARTBOOK_PROFILE_DATA", "available=" + context.profileAvailable()
                + ";instructions=" + context.profileInstructions()
                + ";goal=" + context.profileGoal()
                + ";summary=" + context.profileSummary()
                + ";glossary=" + join(context.profileGlossary())
                + ";defaultStyle=" + context.profileDefaultStyle()
                + ";stableConstraints=" + join(context.profileStableConstraints()));
        append(prompt, "CONFIRMED_MEMORY_DATA", "available=" + context.memoryAvailable()
                + ";decisions=" + join(context.confirmedMemoryDecisions()));
        append(prompt, "ELIGIBLE_ATTACHMENT_CANDIDATES_DATA",
                input.attachmentCandidates().stream()
                        .map(value -> "origin=" + value.origin()
                                + ";ref=" + value.reference().value()
                                + ";mediaType=" + value.mediaType()
                                + ";displayName=" + value.displayName())
                        .toList().toString());
        append(prompt, "SOURCE_SCOPE_DATA", "chartbookMembership="
                + input.chartbookMembership().orElse(""));
        prompt.append("""

                Decide both the requested action and source intent in one pass.
                Source rules:
                - Ordinary drawing or conversation is NO_SOURCE even when a chartbook exists.
                - Use an eligible attachment only when the current request explicitly refers to,
                  transforms, explains, or otherwise requires that attachment.
                - CURRENT_MESSAGE candidates come only from this turn. RECENT_USER_MESSAGE candidates
                  come only from the nearest prior user message with durable attachment bindings.
                - If the request refers to an attachment but no eligible candidate identifies it,
                  return AMBIGUOUS with an empty attachmentRefs array.
                - OPTIONAL_DISCOVERY is only for explicit requests to consult project/chartbook
                  documents without requiring a specific eligible attachment.
                - Never invent an attachment ref. Copy refs only from ELIGIBLE_ATTACHMENT_CANDIDATES_DATA.

                Return one JSON object only with exactly:
                action, outputIntent, targetNeed, diagramType, skillName,
                sourceIntent, sourceConfidence, attachmentRefs, relevanceQuery, sourceReason.
                relevanceQuery must be null when retrieval is not requested.
                """);
        return prompt.toString();
    }

    private void append(StringBuilder target, String name, String value) {
        String normalized = value == null ? "" : value.replace("\u0000", " ");
        target.append('[').append(name).append(" length=").append(normalized.length())
                .append("]\n").append(normalized).append("\n");
    }

    private String join(List<String> values) {
        return values == null ? "[]" : values.toString();
    }
}
