package org.zipp.ai.domain.agent.service.review;

import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.model.valobj.quality.DiagramQualityReport;
import org.zipp.ai.domain.agent.model.valobj.review.CanvasReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.review.CanvasReviewContext;
import org.zipp.ai.domain.agent.model.valobj.review.SemanticContentReview;
import org.zipp.ai.domain.agent.service.ICanvasReviewService;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.IDiagramQualityInspector;
import org.zipp.ai.domain.agent.service.chat.CustomApiConfigManager;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class DefaultCanvasReviewService implements ICanvasReviewService {

    private static final String SEMANTIC_REVIEW_AGENT_ID = "300011";
    private static final String QUALITY_ANSWER_AGENT_ID = "300012";

    @Resource
    private IDiagramQualityInspector diagramQualityInspector;

    @Resource
    private IChatService chatService;

    @Override
    public CanvasReviewContext buildReviewContext(CanvasReviewCommand command) {
        IntentRoutingResult routingResult = command.getRoutingResult();
        String diagramType = null == routingResult ? "unknown" : routingResult.getDiagramType();
        DiagramQualityReport qualityReport = diagramQualityInspector.inspect(command.getMessage(), diagramType);
        boolean needsSemanticReview = null != routingResult && Boolean.TRUE.equals(routingResult.getNeedsSemanticReview());
        SemanticContentReview semanticReview = needsSemanticReview
                ? reviewSemanticContent(command, qualityReport)
                : SemanticContentReview.unavailable("Semantic review was not requested for this turn.");

        String serialized = "[Diagram Quality Report]\n"
                + JSON.toJSONString(qualityReport)
                + "\n\n[Semantic Content Review]\n"
                + JSON.toJSONString(semanticReview);

        return CanvasReviewContext.builder()
                .qualityReport(qualityReport)
                .semanticReview(semanticReview)
                .serializedContext(serialized)
                .build();
    }

    @Override
    public String answer(CanvasReviewCommand command) {
        CanvasReviewContext context = buildReviewContext(command);
        String prompt = "[User Request]\n"
                + command.getMessage()
                + "\n\n[Intent Routing Result]\n"
                + JSON.toJSONString(command.getRoutingResult())
                + "\n\n"
                + context.getSerializedContext();

        try {
            String sessionId = createInternalSession(QUALITY_ANSWER_AGENT_ID, command);
            List<String> outputs = chatService.handleMessage(QUALITY_ANSWER_AGENT_ID, command.getUserId(), sessionId, prompt);
            return parseUserAnswer(String.join("", outputs));
        } catch (Exception e) {
            log.warn("Canvas quality answer failed, fallback to deterministic report summary. userId:{}", command.getUserId(), e);
            return fallbackAnswer(context);
        }
    }

    private SemanticContentReview reviewSemanticContent(CanvasReviewCommand command, DiagramQualityReport qualityReport) {
        String prompt = "[User Request]\n"
                + command.getMessage()
                + "\n\n[Intent Routing Result]\n"
                + JSON.toJSONString(command.getRoutingResult())
                + "\n\n[Diagram Quality Report]\n"
                + JSON.toJSONString(qualityReport);

        try {
            String sessionId = createInternalSession(SEMANTIC_REVIEW_AGENT_ID, command);
            List<String> outputs = chatService.handleMessage(SEMANTIC_REVIEW_AGENT_ID, command.getUserId(), sessionId, prompt);
            String json = extractFirstJsonObject(String.join("", outputs));
            if (null == json) {
                return SemanticContentReview.unavailable("Semantic reviewer did not return valid JSON.");
            }
            return JSON.parseObject(json, SemanticContentReview.class);
        } catch (Exception e) {
            log.warn("Semantic content review failed. userId:{}", command.getUserId(), e);
            return SemanticContentReview.unavailable("Semantic review failed.");
        }
    }

    private String createInternalSession(String agentId, CanvasReviewCommand command) {
        String sessionId = chatService.createSession(agentId, command.getUserId());
        CustomApiConfigManager.CustomApiConfig config = command.getCustomApiConfig();
        if (null != config) {
            CustomApiConfigManager.setConfig(sessionId, config);
        }
        return sessionId;
    }

    private String parseUserAnswer(String raw) {
        String json = extractFirstJsonObject(raw);
        if (null == json) {
            return raw;
        }

        JSONObject object = JSON.parseObject(json);
        String content = object.getString("content");
        return null == content || content.trim().isEmpty() ? raw : content;
    }

    private String fallbackAnswer(CanvasReviewContext context) {
        DiagramQualityReport report = context.getQualityReport();
        return "I reviewed the current canvas quality context.\n\n"
                + "- Overall visual risk: " + report.getOverallRisk() + "\n"
                + "- Nodes: " + report.getNodeCount() + ", edges: " + report.getEdgeCount() + "\n"
                + "- Layout issues: " + report.getLayoutIssues().size() + "\n"
                + "- Readability issues: " + report.getReadabilityIssues().size() + "\n"
                + "- Edge issues: " + report.getEdgeIssues().size() + "\n\n"
                + "Main recommendations: " + String.join("; ", report.getRecommendations());
    }

    private String extractFirstJsonObject(String raw) {
        if (null == raw || raw.isEmpty()) {
            return null;
        }

        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if ('\\' == c) {
                escaped = true;
                continue;
            }
            if ('"' == c) {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if ('{' == c) {
                depth++;
            } else if ('}' == c) {
                depth--;
                if (0 == depth) {
                    return raw.substring(start, i + 1);
                }
            }
        }

        return null;
    }

}
