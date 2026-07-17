package org.zipp.ai.test.domain.agent;

import org.junit.After;
import org.junit.Test;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasPointData;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewEvidence;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewEvidenceRole;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewStage;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.visualreview.ChatCanvasVisualReviewer;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewExecutor;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ChatCanvasVisualReviewerTest {

    private static final String VALID_OUTPUT = """
            {"summary":"The requested change is visible.","issues":[{"type":"TEXT_READABILITY","severity":"minor","targetCellIds":["2"],"anchorLabels":["API"],"region":"center","evidence":"The API label is slightly small.","repairInstruction":"Increase the label size.","repairScope":"local"}],"recommendedHumanReview":false}
            """;
    private final CanvasVisualReviewExecutor reviewExecutor = new CanvasVisualReviewExecutor(1, 20, 1, 20);

    @After
    public void closeExecutor() {
        reviewExecutor.close();
    }

    @Test
    public void sendsBeforeThenAfterPixelsAndUsesAFreshSessionPerReview() {
        List<ChatCommandEntity> captured = new ArrayList<>();
        List<String> sessions = new ArrayList<>();
        IChatService chat = proxy((method, args) -> {
            if (method.getName().equals("createSession")) {
                String session = "session-" + sessions.size();
                sessions.add(session);
                return session;
            }
            if (method.getName().equals("handleMessage") && args.length == 1) {
                captured.add((ChatCommandEntity) args[0]);
                return List.of(VALID_OUTPUT);
            }
            return defaultValue(method.getReturnType());
        });
        ChatCanvasVisualReviewer reviewer = reviewer(chat, 2_000L);

        CanvasVisualReviewResult first = reviewer.review(command(image("before"), image("after")));
        CanvasVisualReviewResult second = reviewer.review(command(null, image("current")));

        assertTrue(first.isAvailable());
        assertTrue(second.isAvailable());
        assertEquals(2, captured.size());
        assertEquals(2, captured.get(0).getInlineDatas().size());
        assertArrayEquals("before".getBytes(StandardCharsets.UTF_8), captured.get(0).getInlineDatas().get(0).getBytes());
        assertArrayEquals("after".getBytes(StandardCharsets.UTF_8), captured.get(0).getInlineDatas().get(1).getBytes());
        assertEquals(1, captured.get(1).getInlineDatas().size());
        assertNotEquals(captured.get(0).getSessionId(), captured.get(1).getSessionId());
        assertFalse(captured.get(0).getTexts().get(0).getMessage().contains(image("after")));
        assertTrue(first.getReviewerVersion().contains("temperature=1.0"));
        assertTrue(first.getReviewerVersion().contains("visual-review-schema-v3"));
        assertEquals(List.of("2"), first.getIssues().get(0).getTargetCellIds());
    }

    @Test
    public void groundsTheReviewWithBoundedNodesAndEdgesWithoutRawXmlOrStyles() {
        AtomicReference<ChatCommandEntity> captured = new AtomicReference<>();
        IChatService chat = proxy((method, args) -> {
            if (method.getName().equals("createSession")) return "session";
            if (method.getName().equals("handleMessage") && args.length == 1) {
                captured.set((ChatCommandEntity) args[0]);
                return List.of(VALID_OUTPUT);
            }
            return defaultValue(method.getReturnType());
        });
        CanvasVisualReviewCommand command = command(null, image("overview"));
        command.setCanvasCells(List.of(
                CanvasCellData.builder().id("2").label("API").kind("node")
                        .style("SECRET_STYLE").rawXml("<mxCell secret='true'/>")
                        .x(40).y(50).width(120).height(60).build(),
                CanvasCellData.builder().id("3").label("Database").kind("node")
                        .x(300).y(50).width(120).height(60).build(),
                CanvasCellData.builder().id("edge-1").label("query").kind("edge")
                        .source("2").target("3")
                        .points(List.of(CanvasPointData.builder().x(200).y(80).build()))
                        .style("SECRET_EDGE_STYLE").rawXml("<mxCell edge='1' secret='true'/>").build()));

        CanvasVisualReviewResult result = reviewer(chat, 2_000L).review(command);

        assertTrue(result.isAvailable());
        String prompt = captured.get().getTexts().get(0).getMessage();
        assertTrue(prompt.contains("\"cellManifest\""));
        assertTrue(prompt.contains("\"nodeCount\":2"));
        assertTrue(prompt.contains("\"edgeCount\":1"));
        assertTrue(prompt.contains("\"id\":\"edge-1\""));
        assertTrue(prompt.contains("\"sourceId\":\"2\""));
        assertTrue(prompt.contains("\"sourceLabel\":\"API\""));
        assertTrue(prompt.contains("\"targetLabel\":\"Database\""));
        assertTrue(prompt.contains("\"waypointCount\":1"));
        assertFalse(prompt.contains("SECRET_STYLE"));
        assertFalse(prompt.contains("SECRET_EDGE_STYLE"));
        assertFalse(prompt.contains("secret='true'"));
    }

    @Test
    public void sendsSupplementalPageAndTilePixelsWithAnExplicitImageManifest() {
        AtomicReference<ChatCommandEntity> captured = new AtomicReference<>();
        IChatService chat = proxy((method, args) -> {
            if (method.getName().equals("createSession")) return "session";
            if (method.getName().equals("handleMessage") && args.length == 1) {
                captured.set((ChatCommandEntity) args[0]);
                return List.of(VALID_OUTPUT);
            }
            return defaultValue(method.getReturnType());
        });
        CanvasVisualReviewCommand command = command(null, image("overview"));
        command.setAdditionalAfterImages(List.of(
                CanvasVisualReviewEvidence.builder()
                        .role(CanvasVisualReviewEvidenceRole.PAGE_OVERVIEW)
                        .pageId("page-2").pageName("Errors").dataUrl(image("page-2")).width(1600).height(900).build(),
                CanvasVisualReviewEvidence.builder()
                        .role(CanvasVisualReviewEvidenceRole.DETAIL_TILE)
                        .pageId("page-1").pageName("Overview").tileIndex(1).tileCount(4)
                        .dataUrl(image("tile-1")).width(1600).height(1200).build()));

        CanvasVisualReviewResult result = reviewer(chat, 2_000L).review(command);

        assertTrue(result.isAvailable());
        assertEquals(3, captured.get().getInlineDatas().size());
        String prompt = captured.get().getTexts().get(0).getMessage();
        assertTrue(prompt.contains("\"role\":\"PAGE_OVERVIEW\""));
        assertTrue(prompt.contains("\"pageName\":\"Errors\""));
        assertTrue(prompt.contains("\"role\":\"DETAIL_TILE\""));
        assertTrue(prompt.contains("\"tileIndex\":1"));
        assertTrue(prompt.contains("Inspect every page overview independently"));
        assertTrue(prompt.contains("Respond in the language named by languageHint"));
        assertFalse(prompt.contains(image("tile-1")));
    }

    @Test
    public void rejectsEverySchemaDeviation() {
        String issue = "{\"type\":\"TEXT_READABILITY\",\"severity\":\"major\",\"targetCellIds\":[\"2\"],\"anchorLabels\":[\"API\"],\"region\":\"center\",\"evidence\":\"visible\",\"repairInstruction\":\"increase size\",\"repairScope\":\"local\"}";
        List<String> invalidOutputs = List.of(
                VALID_OUTPUT.trim().replace("}", ",\"extra\":true}"),
                "```json\n" + VALID_OUTPUT + "\n```",
                VALID_OUTPUT.replace("TEXT_READABILITY", "UNKNOWN_TYPE"),
                "{\"summary\":\"x\",\"issues\":[" + String.join(",", List.of(issue, issue, issue, issue, issue, issue)) + "],\"recommendedHumanReview\":false}",
                VALID_OUTPUT.replace("The API label is slightly small.", "E".repeat(301)));

        for (String output : invalidOutputs) {
            ChatCanvasVisualReviewer reviewer = reviewerReturning(output);
            CanvasVisualReviewResult result = reviewer.review(command(null, image("after")));
            assertFalse("Expected unavailable for: " + output.substring(0, Math.min(output.length(), 40)), result.isAvailable());
            assertEquals("output_schema_error", result.getUnavailableReason());
        }
    }

    @Test
    public void providerFailureReturnsUnavailableInsteadOfEscaping() {
        IChatService chat = proxy((method, args) -> {
            if (method.getName().equals("createSession")) {
                throw new IllegalStateException("secret provider detail");
            }
            return defaultValue(method.getReturnType());
        });

        CanvasVisualReviewResult result = reviewer(chat, 2_000L)
                .review(command(null, image("after")));

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getUnavailableReason());
        assertNotNull(result.getReviewerVersion());
    }

    @Test
    public void timeoutReturnsUnavailable() {
        IChatService chat = proxy((method, args) -> {
            if (method.getName().equals("createSession")) return "session";
            if (method.getName().equals("handleMessage") && args.length == 1) {
                Thread.sleep(100L);
                return List.of(VALID_OUTPUT);
            }
            return defaultValue(method.getReturnType());
        });

        CanvasVisualReviewResult result = reviewer(chat, 5L)
                .review(command(null, image("after")));

        assertFalse(result.isAvailable());
        assertEquals("timeout", result.getUnavailableReason());
    }

    @Test
    public void propagatesReviewTelemetryContextIntoTimeoutWorker() {
        AtomicReference<String> observedRunId = new AtomicReference<>();
        IChatService chat = proxy((method, args) -> {
            if (method.getName().equals("createSession")) return "session";
            if (method.getName().equals("handleMessage") && args.length == 1) {
                AgentUsageTelemetryContext.current().ifPresent(context -> observedRunId.set(context.runId()));
                return List.of(VALID_OUTPUT);
            }
            return defaultValue(method.getReturnType());
        });
        AgentUsageTelemetryContext.RunContext context = new AgentUsageTelemetryContext.RunContext(
                "aru_visual_test", "request-1", "diagram-1", "usr-1", "300018",
                "visual_review", "PLATFORM", null, "openai", "vlm-1", "visual_review");

        try (AgentUsageTelemetryContext.Scope ignored = AgentUsageTelemetryContext.bind(context)) {
            reviewer(chat, 2_000L)
                    .review(command(null, image("after")));
        }

        assertEquals("aru_visual_test", observedRunId.get());
    }

    @Test
    public void productionReviewerConfigurationHasNoTools() throws Exception {
        String yaml = new String(getClass().getResourceAsStream("/agent/agent-draw-io.yml").readAllBytes(), StandardCharsets.UTF_8);
        int start = yaml.indexOf("drawIoVisualReviewAgent:");
        int end = yaml.indexOf("drawIoEvalDraftAgent:", start);
        String table = yaml.substring(start, end);

        assertTrue(table.contains("${ZIPP_VISUAL_REVIEW_AGENT_ID:300018}"));
        assertTrue(table.contains("agent_visual_reviewer"));
        assertTrue(table.contains("temperature: ${VLM_TEMPERATURE:1}"));
        assertFalse(table.contains("tool-mcp-list"));
        assertFalse(table.contains("drawioCanvasToolCallbackProvider"));
    }

    @Test
    public void productionReviewerConfigurationAllowsSlowVisualCallsAndReportsTheResolvedModel() throws Exception {
        String yaml = new String(getClass().getResourceAsStream("/application.yml").readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(yaml.contains("timeout-ms: ${ZIPP_VISUAL_REVIEW_TIMEOUT_MS:60000}"));
        assertTrue(yaml.contains("model-version: ${VLM_MODEL:${LLM_MODEL:gpt-5.5}}"));
    }

    private ChatCanvasVisualReviewer reviewerReturning(String output) {
        IChatService chat = proxy((method, args) -> {
            if (method.getName().equals("createSession")) return "session";
            if (method.getName().equals("handleMessage") && args.length == 1) return List.of(output);
            return defaultValue(method.getReturnType());
        });
        return reviewer(chat, 2_000L);
    }

    private ChatCanvasVisualReviewer reviewer(IChatService chat, long timeoutMillis) {
        return new ChatCanvasVisualReviewer(chat, "300018", "vlm-1", 1D, timeoutMillis, reviewExecutor);
    }

    private CanvasVisualReviewCommand command(String before, String after) {
        return CanvasVisualReviewCommand.builder()
                .stage(CanvasVisualReviewStage.POST_MUTATION)
                .originalUserTask("Make the API readable")
                .diagramType("architecture")
                .beforeImageDataUrl(before)
                .afterImageDataUrl(after)
                .analyzerEvidence(List.of("No deterministic overlap"))
                .canvasSummary("2 nodes, 1 edge")
                .languageHint("en")
                .rendererVersion("drawio-embed-png-v1")
                .build();
    }

    private String image(String pixels) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(pixels.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private IChatService proxy(Invocation invocation) {
        return (IChatService) Proxy.newProxyInstance(IChatService.class.getClassLoader(), new Class<?>[]{IChatService.class},
                (value, method, args) -> invocation.call(method, args == null ? new Object[0] : args));
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return 0D;
    }

    private interface Invocation {
        Object call(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
