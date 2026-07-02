package org.zipp.ai.domain.agent.service.intent;

import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.IIntentRoutingService;
import org.zipp.ai.domain.agent.service.chat.CustomApiConfigManager;
import org.zipp.ai.types.util.SecretLogSanitizer;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class DefaultIntentRoutingService implements IIntentRoutingService {

    private static final String INTENT_AGENT_ID = "300010";

    @Resource
    private IChatService chatService;

    @Resource
    private org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService skillCatalogService;

    // <mxGraphModel>...</mxGraphModel> embedded in the message means an existing canvas is in play.
    private static final java.util.regex.Pattern MXGRAPH_PATTERN =
            java.util.regex.Pattern.compile("<mxGraphModel[\\s\\S]*?</mxGraphModel>");

    // Phrases that unambiguously mean "tweak a label/style on the existing canvas".
    private static final String[] PATCH_VERBS = {
            "改成", "改为", "换成", "改名", "重命名", "改文字", "改文案", "改标签", "改名字",
            "换个名", "换名字", "改颜色", "换颜色", "改色", "换色", "改配色", "改底色", "改字体", "加粗",
            "rename", "relabel", "recolor"
    };

    // If any of these appear, the change is structural / additive / destructive / layout —
    // hand it to the LLM router instead of the fast patch path.
    private static final String[] PATCH_BLOCKERS = {
            "添加", "新增", "增加", "再加", "加一个", "加个", "补充",
            "删除", "删掉", "移除", "去掉", "移动", "挪",
            "重新画", "重画", "重新生成", "新建", "画一", "画个", "画张", "生成一",
            "布局", "排版", "方向", "横版", "竖版", "重排", "对齐", "美化", "优化",
            "add", "delete", "remove", "move", "create", "redraw", "layout", "optimize"
    };

    private static final String[] VISUAL_REVIEW_TERMS = {
            "线重叠", "重叠", "整理", "布局", "排版", "对齐", "间距", "线", "箭头", "美化",
            "框", "边框", "容器", "分组", "component 框", "component框",
            "overlap", "layout", "spacing", "align", "alignment", "edge", "line", "arrow",
            "frame", "container", "group", "component frame", "visual"
    };

    private static final String[] SEMANTIC_REVIEW_TERMS = {
            "新增", "删除", "移除", "业务", "概念", "关系", "依赖", "连接", "连到", "正确",
            "合理", "专业", "缺少", "模块", "服务", "流程语义", "语义",
            "business", "concept", "relationship", "dependency", "connect", "correct",
            "correctness", "missing", "module", "service", "semantic"
    };

    @Override
    public IntentRoutingResult route(IntentRoutingCommand command) {
        String userId = null == command ? "" : command.getUserId();
        IntentRoutingResult fastPath = tryFastPatchRoute(command);
        if (null != fastPath) {
            logRoutingDecision("fast_path", userId, fastPath);
            return fastPath;
        }
        try {
            String sessionId = chatService.createSession(INTENT_AGENT_ID, userId);
            CustomApiConfigManager.CustomApiConfig config = command.getCustomApiConfig();
            if (null != config) {
                CustomApiConfigManager.setConfig(sessionId, config);
            }

            // Feed the live skill catalog so the router can pick ANY available skill by description
            // (including the user's own), instead of a hardcoded enum.
            String routerMessage = withAvailableSkills(command.getMessage(), userId);
            List<String> outputs = chatService.handleMessage(INTENT_AGENT_ID, userId, sessionId, routerMessage);
            String rawResult = String.join("", outputs);
            IntentRoutingResult result = normalize(parseRoutingResult(rawResult),
                    extractUserInstruction(null == command ? "" : command.getMessage()));
            logRoutingDecision("llm", userId, result);
            return result;
        } catch (Exception e) {
            log.warn("Intent routing failed, fallback to drawing workflow. userId:{}",
                    SecretLogSanitizer.maskCapability(userId), e);
            IntentRoutingResult fallback = IntentRoutingResult.fallbackDrawAction("Intent routing failed; fallback to drawing workflow.");
            logRoutingDecision("fallback", userId, fallback);
            return fallback;
        }
    }

    /**
     * Skip the LLM router for the most common micro-edit: an existing canvas plus a clear
     * relabel/recolor instruction. The router only returns the high-level edit_existing task; the
     * drawer chooses the concrete modify_diagram operation later from the current XML and tool schema.
     */
    private IntentRoutingResult tryFastPatchRoute(IntentRoutingCommand command) {
        String message = null == command ? "" : command.getMessage();
        if (null == message || message.trim().isEmpty()) {
            return null;
        }
        // An empty canvas still ships an <mxGraphModel> skeleton, so require a real vertex/edge.
        if (!hasExistingCanvas(null == command ? "" : command.getCanvasXml(), message)) {
            return null; // No drawable canvas -> let the router decide (likely create_new).
        }
        // Match verbs only against the user's request, not injected canvas or policy text.
        String instruction = extractUserInstruction(message).toLowerCase();
        if (!containsAny(instruction, PATCH_VERBS) || containsAny(instruction, PATCH_BLOCKERS)) {
            return null;
        }

        IntentRoutingResult result = new IntentRoutingResult();
        result.setIntent("draw_action");
        result.setDrawMode("edit_existing");
        result.setDiagramType("basic");
        result.setSkillName("none");
        result.setTaskType("edit_existing");
        result.setNeedsCanvasQuality(false);
        result.setNeedsSemanticReview(false);
        result.setAnswerMode("none");
        result.setAnswer("");
        result.setReason("Rule-based fast path: existing canvas with a localized relabel/recolor edit.");
        return result;
    }

    // The frontend embeds the live canvas as [Context: Current Draw.io XML]; an empty canvas is just
    // the <mxGraphModel> skeleton with cells 0/1, so a real vertex or edge marks an existing canvas.
    private boolean hasExistingCanvas(String canvasXml, String legacyMessage) {
        String source = (null != canvasXml && !canvasXml.isBlank()) ? canvasXml : legacyMessage;
        if (!MXGRAPH_PATTERN.matcher(source).find()) {
            return false;
        }
        return source.contains("vertex=\"1\"") || source.contains("vertex='1'")
                || source.contains("edge=\"1\"") || source.contains("edge='1'");
    }

    // Pull out just the user's request (after the [User Request] marker the frontend appends),
    // stripping any embedded XML; falls back to the whole message for raw API callers.
    private String extractUserInstruction(String message) {
        int marker = message.lastIndexOf("[User Request]");
        String tail = marker >= 0 ? message.substring(marker + "[User Request]".length()) : message;
        return MXGRAPH_PATTERN.matcher(tail).replaceAll(" ");
    }

    private boolean containsAny(String haystack, String[] needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // Prepend the dynamic skill catalog so skill selection is description-driven, not a fixed list.
    private String withAvailableSkills(String message, String ownerId) {
        String catalog = skillCatalogService.catalogText(ownerId);
        if (null == catalog || catalog.isBlank()) {
            return message;
        }
        return "[Available Skills] (choose skillName from these by matching the request to the description, or \"none\")\n"
                + catalog
                + "\n"
                + message;
    }

    private IntentRoutingResult parseRoutingResult(String rawResult) {
        String json = extractFirstJsonObject(rawResult);
        if (null == json) {
            return IntentRoutingResult.fallbackDrawAction("Intent router did not return valid JSON.");
        }
        return JSON.parseObject(json, IntentRoutingResult.class);
    }

    private IntentRoutingResult normalize(IntentRoutingResult result, String userInstruction) {
        if (null == result || null == result.getIntent()) {
            return IntentRoutingResult.fallbackDrawAction("Intent router returned an empty decision.");
        }

        if (result.isDirectReply()) {
            result.setDrawMode("none");
            result.setDiagramType("none");
            result.setSkillName("none");
            result.setTaskType("none");
            normalizeReviewFlags(result, userInstruction);
            if (null == result.getAnswerMode() || result.getAnswerMode().trim().isEmpty()) {
                result.setAnswerMode(result.needsCanvasReview() ? "quality_review" : "general");
            }
            if (null == result.getAnswer() || result.getAnswer().trim().isEmpty()) {
                result.setAnswer("Please provide a little more detail about what you want to do with the Draw.io canvas.");
            }
            return result;
        }

        if (!result.isDrawAction()) {
            return IntentRoutingResult.fallbackDrawAction("Unknown intent; fallback to drawing workflow.");
        }

        if (null == result.getDrawMode() || result.getDrawMode().trim().isEmpty()) {
            result.setDrawMode("new_diagram");
        }
        if (null == result.getDiagramType() || result.getDiagramType().trim().isEmpty()) {
            result.setDiagramType("basic");
        }
        if (null == result.getSkillName() || result.getSkillName().trim().isEmpty()) {
            result.setSkillName("none");
        }
        normalizeTaskType(result);
        normalizeReviewFlags(result, userInstruction);
        if (null == result.getAnswerMode() || result.getAnswerMode().trim().isEmpty()) {
            result.setAnswerMode("none");
        }
        result.setAnswer("");
        return result;
    }

    private void normalizeTaskType(IntentRoutingResult result) {
        String taskType = normalizeLegacyTaskType(result.getTaskType());
        if (null != taskType && !taskType.isBlank()) {
            result.setTaskType(taskType);
            return;
        }
        String drawMode = result.getDrawMode();
        String answerMode = result.getAnswerMode();
        String reason = null == result.getReason() ? "" : result.getReason().toLowerCase();
        if ("new_diagram".equals(drawMode)) {
            result.setTaskType("create_new");
        } else if (reason.contains("optimize") || reason.contains("layout") || reason.contains("rearrange")) {
            result.setTaskType("optimize_layout");
        } else if ("quality_review".equals(answerMode) || "semantic_review".equals(answerMode) || "quality_and_semantic_review".equals(answerMode)) {
            result.setTaskType("review_only");
        } else if ("edit_existing".equals(drawMode)) {
            result.setTaskType("edit_existing");
        } else {
            result.setTaskType("create_new");
        }
    }

    private String normalizeLegacyTaskType(String taskType) {
        String normalized = null == taskType ? "" : taskType.trim();
        String mapped = switch (normalized) {
            case "", "none", "create_new", "edit_existing", "optimize_layout", "review_only" -> normalized;
            case "patch_existing", "append_existing" -> "edit_existing";
            case "fallback_full_xml" -> "create_new";
            default -> "";
        };
        if (!normalized.isBlank() && !normalized.equals(mapped)) {
            log.info("[intent-route] source=legacy_task_type legacyTaskType={} normalizedTaskType={}",
                    logValue(normalized), logValue(mapped));
        }
        return mapped;
    }

    private void normalizeReviewFlags(IntentRoutingResult result, String userInstruction) {
        if (null == result.getNeedsCanvasQuality()) {
            result.setNeedsCanvasQuality(false);
        }
        if (null == result.getNeedsSemanticReview()) {
            result.setNeedsSemanticReview(false);
        }
        if (Boolean.TRUE.equals(result.getNeedsSemanticReview())
                && shouldSuppressSemanticReview(result, userInstruction)) {
            log.info("[intent-route] semantic_review_suppressed taskType={} reason={} userInstruction={}",
                    logValue(result.getTaskType()),
                    logValue(result.getReason()),
                    logValue(userInstruction));
            result.setNeedsSemanticReview(false);
        }
    }

    private boolean shouldSuppressSemanticReview(IntentRoutingResult result, String userInstruction) {
        if ("optimize_layout".equals(result.getTaskType())) {
            return true;
        }

        String text = (String.valueOf(userInstruction) + " " + String.valueOf(result.getReason()))
                .toLowerCase(Locale.ROOT);
        return containsAny(text, VISUAL_REVIEW_TERMS) && !containsAny(text, SEMANTIC_REVIEW_TERMS);
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

    private void logRoutingDecision(String source, String userId, IntentRoutingResult result) {
        if (null == result) {
            return;
        }
        log.info("[intent-route] source={} userId={} intent={} drawMode={} taskType={} diagramType={} skillName={} canvasReview={} semanticReview={} answerMode={} reason={}",
                logValue(source),
                SecretLogSanitizer.maskCapability(userId),
                logValue(result.getIntent()),
                logValue(result.getDrawMode()),
                logValue(result.getTaskType()),
                logValue(result.getDiagramType()),
                logValue(result.getSkillName()),
                result.getNeedsCanvasQuality(),
                result.getNeedsSemanticReview(),
                logValue(result.getAnswerMode()),
                logValue(result.getReason()));
    }

    private String logValue(String value) {
        if (null == value) {
            return "";
        }
        String compact = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return compact.length() <= 160 ? compact : compact.substring(0, 160) + "...";
    }

}
