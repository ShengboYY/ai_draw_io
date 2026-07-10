package org.zipp.ai.domain.agent.model.valobj.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Versioned evaluation case contract shared by hand-authored and trace-derived cases.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCaseDefinition {

    private String caseId;
    private String datasetVersion;
    private String origin;
    private String risk;
    private String diagramType;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> input = Map.of();

    @Builder.Default
    private Privacy privacy = new Privacy();

    @Builder.Default
    private Expected expected = new Expected();

    @Builder.Default
    private Replay replay = new Replay();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Privacy {
        private String classification;
        private String sanitizerVersion;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Expected {
        private String routeType;
        private EvalTrace.TaskOutcome taskOutcome;

        @Builder.Default
        private List<String> requiredToolNamesBeforeMutation = new ArrayList<>();

        @Builder.Default
        private List<String> allowedMutationTools = new ArrayList<>();

        private Boolean requireCanvasChange;
        private Integer maxCriticalIssues;
        private Integer maxMajorIssues;
    }

    /** Recorded deterministic inputs for Mode B; production cases must not share a global replay script. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Replay {
        private String initialCanvasXml;
        private String routerReply;
        private String toolName;
        private String mutationMode;
        private String mutationCells;
        private EvalTrace.TaskOutcome taskOutcome;
    }
}
