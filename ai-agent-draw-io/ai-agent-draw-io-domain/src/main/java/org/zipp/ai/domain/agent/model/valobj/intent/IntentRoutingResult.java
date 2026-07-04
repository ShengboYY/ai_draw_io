package org.zipp.ai.domain.agent.model.valobj.intent;

import lombok.Data;

@Data
public class IntentRoutingResult {

    private String intent;

    private String drawMode;

    private String diagramType;

    private String skillName;

    private String taskType;

    private Boolean needsCanvasQuality;

    private Boolean needsSemanticReview;

    private String answerMode;

    private String answer;

    private String reason;

    public boolean isDirectReply() {
        return "answer_only".equals(intent) || "clarify".equals(intent);
    }

    public boolean isDrawAction() {
        return "draw_action".equals(intent);
    }

    public boolean needsCanvasReview() {
        return Boolean.TRUE.equals(needsCanvasQuality) || Boolean.TRUE.equals(needsSemanticReview);
    }

    public static IntentRoutingResult fallbackDrawAction(String reason) {
        IntentRoutingResult result = new IntentRoutingResult();
        result.setIntent("draw_action");
        result.setDrawMode("new_diagram");
        result.setDiagramType("diagram");
        result.setSkillName("none");
        result.setTaskType("create_new");
        result.setNeedsCanvasQuality(false);
        result.setNeedsSemanticReview(false);
        result.setAnswerMode("none");
        result.setAnswer("");
        result.setReason(reason);
        return result;
    }

}
