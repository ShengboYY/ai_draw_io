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
        boolean directAvailable = command.hasSingleReadyImageAttachment();
        if (use == SourceUse.DIRECT && !directAvailable) use = SourceUse.NONE;
        if (use == SourceUse.DIRECT_AND_RETRIEVAL && !directAvailable) use = SourceUse.RETRIEVAL;
        // The explicit no-material declaration is authoritative over a model-provided source-use hint.
        if (command.retrievalMode() == SourceMode.NONE) {
            if (use == SourceUse.RETRIEVAL) use = SourceUse.NONE;
            if (use == SourceUse.DIRECT_AND_RETRIEVAL) use = SourceUse.DIRECT;
        }

        List<String> directIds = usesDirect(use) ? command.readyAttachmentVersionIds() : List.of();
        boolean strict = command.retrievalMode() == SourceMode.EXPLICIT_ONLY;
        return new TaskSourcePlan(command.action(), use, command.retrievalMode(), directIds,
                usesRetrieval(use) ? command.selectedReferenceVersionIds() : List.of(), strict,
                usesDirect(use));
    }

    private TaskSourcePlan none(TaskSourcePlanningCommand command) {
        return new TaskSourcePlan(command.action(), SourceUse.NONE, SourceMode.NONE,
                List.of(), List.of(), false, false);
    }

    private boolean usesDirect(SourceUse use) {
        return use == SourceUse.DIRECT || use == SourceUse.DIRECT_AND_RETRIEVAL;
    }

    private boolean usesRetrieval(SourceUse use) {
        return use == SourceUse.RETRIEVAL || use == SourceUse.DIRECT_AND_RETRIEVAL;
    }
}
