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
                + ";defaultStyle=" + context.profileDefaultStyle());
        append(prompt, "CONFIRMED_MEMORY_DATA", "available=" + context.memoryAvailable()
                + ";decisions=" + join(context.confirmedMemoryDecisions()));
        prompt.append("\nReturn one JSON object only with action, outputIntent, targetNeed, diagramType, skillName.");
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
