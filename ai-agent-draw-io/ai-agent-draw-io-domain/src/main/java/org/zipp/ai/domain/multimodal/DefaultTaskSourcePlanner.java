package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;
import java.util.Objects;

/** Enforces source invariants before any visual reader, retrieval adapter, or canvas mutation runs. */
public final class DefaultTaskSourcePlanner implements TaskSourcePlanner {

    @Override
    public TaskSourcePlan plan(TaskSourcePlanningCommand command) {
        Objects.requireNonNull(command, "command");

        // Layout work is derived exclusively from the current canvas and must not trigger material access.
        if (command.action() == CanvasAction.OPTIMIZE_LAYOUT) {
            return none(command);
        }

        SourceUse use = command.requestedSourceUse();
        if (command.action() != CanvasAction.CREATE) {
            // Direct image reconstruction only creates a replacement canvas in this release.
            if (use == SourceUse.DIRECT) use = SourceUse.NONE;
            if (use == SourceUse.DIRECT_AND_RETRIEVAL) use = SourceUse.RETRIEVAL;
        }
        DirectSelection direct = usesDirect(use) ? selectDirect(command) : DirectSelection.none();
        if (usesDirect(use) && direct.unavailable()) {
            use = use == SourceUse.DIRECT_AND_RETRIEVAL ? SourceUse.RETRIEVAL : SourceUse.NONE;
        }
        // The explicit no-material declaration is authoritative over a model-provided source-use hint.
        if (command.retrievalMode() == SourceMode.NONE) {
            if (use == SourceUse.RETRIEVAL) use = SourceUse.NONE;
            if (use == SourceUse.DIRECT_AND_RETRIEVAL) use = SourceUse.DIRECT;
        }

        boolean strict = command.retrievalMode() == SourceMode.EXPLICIT_ONLY;
        return new TaskSourcePlan(command.action(), use, command.retrievalMode(),
                usesDirect(use) ? direct.versionId() : "",
                usesRetrieval(use) ? command.selectedReferenceVersionIds() : List.of(), strict,
                usesDirect(use) && direct.selected(), usesDirect(use) ? direct.clarificationReason() : "");
    }

    private TaskSourcePlan none(TaskSourcePlanningCommand command) {
        return new TaskSourcePlan(command.action(), SourceUse.NONE, SourceMode.NONE,
                "", List.of(), false, false, "");
    }

    private DirectSelection selectDirect(TaskSourcePlanningCommand command) {
        List<String> newlyUploaded = command.newlyUploadedDirectCandidateVersionIds();
        if (newlyUploaded.size() == 1) {
            return DirectSelection.selected(newlyUploaded.get(0));
        }
        List<String> explicitlySelected = command.explicitlySelectedDirectCandidateVersionIds();
        if (explicitlySelected.size() == 1) {
            return DirectSelection.selected(explicitlySelected.get(0));
        }
        if (explicitlySelected.size() > 1) {
            return DirectSelection.clarification("AMBIGUOUS_DIRECT_IMAGE");
        }
        if (!command.namedDirectCandidateVersionId().isEmpty()) {
            return DirectSelection.selected(command.namedDirectCandidateVersionId());
        }
        if (command.conversationDirectCandidateVersionIds().size() == 1) {
            return DirectSelection.selected(command.conversationDirectCandidateVersionIds().get(0));
        }
        if (!command.directCandidateVersionIds().isEmpty()) {
            // Diagram and Chartbook images require a unique name; unqualified images are a user choice.
            return DirectSelection.clarification("AMBIGUOUS_DIRECT_IMAGE");
        }
        return DirectSelection.none();
    }

    private boolean usesDirect(SourceUse use) {
        return use == SourceUse.DIRECT || use == SourceUse.DIRECT_AND_RETRIEVAL;
    }

    private boolean usesRetrieval(SourceUse use) {
        return use == SourceUse.RETRIEVAL || use == SourceUse.DIRECT_AND_RETRIEVAL;
    }

    private record DirectSelection(String versionId, String clarificationReason) {
        private static DirectSelection selected(String versionId) {
            return new DirectSelection(versionId, "");
        }

        private static DirectSelection clarification(String reason) {
            return new DirectSelection("", reason);
        }

        private static DirectSelection none() {
            return new DirectSelection("", "");
        }

        private boolean selected() {
            return !versionId.isEmpty();
        }

        private boolean unavailable() {
            return !selected() && clarificationReason.isEmpty();
        }
    }
}
