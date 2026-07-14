package org.zipp.ai.domain.agent.model.valobj.intent;

import lombok.Data;

@Data
public class IntentRoutingResult {

    private String routeType;

    private String diagramType;

    private String skillName;

    private String answer;

    private String reason;

    public boolean isDirectReply() {
        return "answer_only".equals(routeType)
                || "clarify".equals(routeType)
                || "review_only".equals(routeType);
    }

    public boolean isDrawAction() {
        return "create_new".equals(routeType)
                || "edit_existing".equals(routeType)
                || "optimize_layout".equals(routeType);
    }

    public static IntentRoutingResult clarifyFallback(String reason) {
        // Fail closed: malformed or untrusted routing output must never mutate the user's canvas.
        IntentRoutingResult result = new IntentRoutingResult();
        result.setRouteType("clarify");
        result.setDiagramType("none");
        result.setSkillName("none");
        result.setAnswer("抱歉，我没能理解这次请求。请再说清楚一点你想对 Draw.io 画布做什么（新建 / 修改 / 查看）。\n"
                + "Sorry, I couldn't parse that request - please clarify what you'd like to do with the Draw.io canvas (create / edit / review).");
        result.setReason(reason);
        return result;
    }

}
