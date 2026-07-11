package org.zipp.ai.domain.agent.model.valobj.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Stable, non-sensitive trajectory consumed by deterministic evaluation graders.
 * Production telemetry and deterministic replays must both project into this shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalTrace {

    private RunStatus runStatus;
    private TaskOutcome taskOutcome;
    private Routing routing;

    @Builder.Default
    private List<Step> steps = new ArrayList<>();

    @Builder.Default
    private List<ToolCall> toolCalls = new ArrayList<>();

    private String beforeCanvasHash;
    private String afterCanvasHash;

    public enum RunStatus {
        SUCCESS,
        FAILED
    }

    public enum TaskOutcome {
        FULFILLED,
        NOT_FULFILLED,
        SAFE_REFUSAL,
        CLARIFICATION_NEEDED,
        PARTIAL,
        UNKNOWN
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Routing {
        private String routeType;
        private String diagramType;
        private String skillName;
        private Boolean needsCanvasQuality;
        private Boolean needsSemanticReview;
        private String answerMode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Step {
        private String phase;
        private String agentId;
        private RunStatus status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        private String name;
        private RunStatus status;
    }
}
