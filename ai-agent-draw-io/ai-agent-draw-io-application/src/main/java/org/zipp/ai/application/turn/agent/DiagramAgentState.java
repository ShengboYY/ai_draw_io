package org.zipp.ai.application.turn.agent;

import org.zipp.ai.application.turn.PlainGenerationRequest;
import org.zipp.ai.application.turn.skill.DiagramSkillBundle;

import java.util.List;

/** Explicit immutable runtime state reduced after every real environment result. */
public record DiagramAgentState(
        PlainGenerationRequest request,
        DiagramSkillBundle skills,
        DiagramDraftView activeDraft,
        DiagramDraftAnalysis latestAnalysis,
        DiagramDraftVisualReview latestVisualReview,
        DiagramAgentToolResult latestToolResult,
        List<DiagramAgentStepRecord> steps,
        List<String> recentDraftDigests,
        int stepCount,
        int mutationCount,
        int createCallCount,
        int fullXmlInspectionCount,
        int visualReviewCount,
        int noProgressCount
) {

    public DiagramAgentState {
        if (request == null || skills == null
                || stepCount < 0 || mutationCount < 0 || createCallCount < 0
                || fullXmlInspectionCount < 0 || visualReviewCount < 0
                || noProgressCount < 0) {
            throw new IllegalArgumentException("diagram agent state is invalid");
        }
        steps = List.copyOf(steps == null ? List.of() : steps);
        recentDraftDigests = List.copyOf(
                recentDraftDigests == null ? List.of() : recentDraftDigests);
    }
}
