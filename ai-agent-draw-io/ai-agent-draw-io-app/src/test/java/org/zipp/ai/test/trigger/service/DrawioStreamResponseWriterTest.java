package org.zipp.ai.test.trigger.service;

import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateVersionConflictException;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;
import org.zipp.ai.trigger.http.service.DrawioStreamResponseWriter;
import org.zipp.ai.trigger.http.service.DrawioToolCallRenderer;

import java.lang.reflect.Field;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DrawioStreamResponseWriterTest {

    @Test
    public void shouldSendRouteAsACompactThinkingEvent() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();

        writer.sendRoute(emitter, "edit_existing");

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"phase\":\"thinking\""));
        assertTrue(output.contains("\"type\":\"route\""));
        assertTrue(output.contains("\"routeType\":\"edit_existing\""));
    }

    @Test
    public void shouldStreamVisualWarningsImmediately() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"display_diagram","xml":"<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='80' as='geometry'/></mxCell><mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='120' y='120' width='140' height='80' as='geometry'/></mxCell>"}
                """);

        String beforeFlush = String.join("\n", emitter.sent);
        assertTrue(beforeFlush.contains("\"type\":\"validation_result\""));
        assertTrue(beforeFlush.contains("\"valid\":false"));
        assertTrue(beforeFlush.contains("Overlapping nodes"));
        assertTrue(beforeFlush.contains("\"type\":\"drawio_node\""));
        assertFalse(beforeFlush.contains("\"type\":\"drawio_done\""));

        writer.flushPendingDiagram(emitter, "done");

        String afterFlush = String.join("\n", emitter.sent);
        assertEquals(1, countOccurrences(afterFlush, "\"type\":\"drawio_done\""));
        assertTrue(afterFlush.contains("value='A'"));
        assertTrue(afterFlush.contains("value='B'"));
    }

    @Test
    public void shouldHoldCriticalStructuralErrorsUntilReviewBudgetIsExhausted() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"display_diagram","xml":"<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='80' as='geometry'/></mxCell><mxCell id='3' value='broken' edge='1' parent='1' source='2' target='404'><mxGeometry relative='1' as='geometry'/></mxCell>"}
                """);

        String beforeFlush = String.join("\n", emitter.sent);
        assertTrue(beforeFlush.contains("\"type\":\"validation_result\""));
        assertTrue(beforeFlush.contains("target id does not exist"));
        assertFalse(beforeFlush.contains("\"type\":\"drawio_node\""));
        assertFalse(beforeFlush.contains("\"type\":\"drawio_done\""));

        writer.flushPendingDiagram(emitter, "done");

        String afterFlush = String.join("\n", emitter.sent);
        assertTrue(afterFlush.contains("\"type\":\"error\""));
        assertFalse(afterFlush.contains("\"type\":\"drawio_done\""));
    }

    @Test
    public void shouldSendValidDiagramOnlyAfterFinalization() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"display_diagram","xml":"<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='80' as='geometry'/></mxCell><mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='320' y='100' width='140' height='80' as='geometry'/></mxCell>"}
                """);

        String beforeFlush = String.join("\n", emitter.sent);
        assertFalse(beforeFlush.contains("\"type\":\"drawio_done\""));

        writer.flushPendingDiagram(emitter, "done");

        String afterFlush = String.join("\n", emitter.sent);
        assertEquals(1, countOccurrences(afterFlush, "\"type\":\"drawio_done\""));
    }

    @Test
    public void shouldStreamRawGraphModelAsPreviewNodesEdgesAndFinalDiagram() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();

        writer.sendDrawioStream(emitter, "drawing", """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='User' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell><mxCell id='3' value='API' vertex='1' parent='1'><mxGeometry x='300' y='100' width='120' height='60' as='geometry'/></mxCell><mxCell id='4' value='calls' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell></root></mxGraphModel>
                """);
        writer.flushPendingDiagram(emitter, "done");

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"drawio_preview\""));
        assertEquals(2, countOccurrences(output, "\"type\":\"drawio_node\""));
        assertEquals(1, countOccurrences(output, "\"type\":\"drawio_edge\""));
        assertTrue(output.contains("\"type\":\"validation_result\""));
        assertTrue(output.contains("\"type\":\"drawio_done\""));
    }

    @Test
    public void shouldStreamRawGraphModelLineAsPreviewNodesEdgesAndFinalDiagram() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();

        writer.processAndSendLine(emitter, "drawing", """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='User' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell><mxCell id='3' value='API' vertex='1' parent='1'><mxGeometry x='300' y='100' width='120' height='60' as='geometry'/></mxCell><mxCell id='4' value='calls' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell></root></mxGraphModel>
                """);
        writer.flushPendingDiagram(emitter, "done");

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"drawio_preview\""));
        assertEquals(2, countOccurrences(output, "\"type\":\"drawio_node\""));
        assertEquals(1, countOccurrences(output, "\"type\":\"drawio_edge\""));
        assertTrue(output.contains("\"type\":\"drawio_done\""));
    }




    @Test
    public void shouldBufferFallbackContinueDiagramLines() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"continue_diagram","continuationId":"fallback","xmlFragment":"<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>","done":false}
                """);
        writer.processAndSendLine(emitter, "drawing", """
                {"type":"continue_diagram","continuationId":"fallback","xmlFragment":"<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='300' y='100' width='120' height='60' as='geometry'/></mxCell>","done":true}
                """);
        writer.flushPendingDiagram(emitter, "done");

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"continuation_result\""));
        assertTrue(output.contains("\"type\":\"drawio_node\""));
        assertTrue(output.contains("value='A'"));
        assertTrue(output.contains("value='B'"));
        assertTrue(output.contains("\"type\":\"drawio_done\""));
    }

    @Test
    public void shouldMergeConsolidatedModifyPatchFallback() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();
        writer.setCurrentCanvas(emitter, """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"modify_diagram","mode":"patch","cells":"<mxCell id='2' value='API v2' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>"}
                """);
        writer.flushPendingDiagram(emitter, "done");

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"drawio_done\""));
        assertTrue(output.contains("\"mode\":\"local\""));
        assertTrue(output.contains("API v2"));
    }

    @Test
    public void shouldMergeConsolidatedModifyAppendFallback() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();
        writer.setCurrentCanvas(emitter, """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"modify_diagram","mode":"append","cells":"<mxCell id='3' value='Worker' vertex='1' parent='1'><mxGeometry x='320' y='100' width='120' height='60' as='geometry'/></mxCell>"}
                """);
        writer.flushPendingDiagram(emitter, "done");

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"drawio_done\""));
        assertTrue(output.contains("\"mode\":\"local\""));
        assertTrue(output.contains("API"));
        assertTrue(output.contains("Worker"));
    }

    @Test
    public void shouldMergeConsolidatedModifyReplaceCellsFallback() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();
        writer.setCurrentCanvas(emitter, """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Service' vertex='1' parent='1'><mxGeometry x='300' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"modify_diagram","mode":"replace_cells","cells":"<mxCell id='2' value='Gateway' vertex='1' parent='1'><mxGeometry x='100' y='100' width='160' height='60' as='geometry'/></mxCell>"}
                """);
        writer.flushPendingDiagram(emitter, "done");

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"drawio_done\""));
        assertTrue(output.contains("\"mode\":\"local\""));
        assertTrue(output.contains("Gateway"));
        assertTrue(output.contains("Service"));
        assertFalse(output.contains("API"));
    }

    @Test
    public void shouldAdvanceCurrentCanvasAfterLocalPatchMerge() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();
        writer.setCurrentCanvas(emitter, """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Service' vertex='1' parent='1'><mxGeometry x='300' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"patch_cells","cells":"<mxCell id='2' value='API v2' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>"}
                """);
        writer.processAndSendLine(emitter, "drawing", """
                {"type":"patch_cells","cells":"<mxCell id='3' value='Service v2' vertex='1' parent='1'><mxGeometry x='300' y='100' width='150' height='60' as='geometry'/></mxCell>"}
                """);
        writer.flushPendingDiagram(emitter, "done");

        String finalChunk = emitter.sent.get(emitter.sent.size() - 1);
        assertTrue(finalChunk.contains("API v2"));
        assertTrue(finalChunk.contains("Service v2"));
        assertTrue(finalChunk.contains("\"mode\":\"local\""));
    }

    @Test
    public void shouldReportLocalPatchAsSentAfterStreamingMergedCanvas() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();
        writer.setCurrentCanvas(emitter, """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        boolean sent = writer.sendLocalCellPatch(emitter, "drawing", "", """
                <mxCell id='2' value='API v2' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>
                """);
        writer.flushPendingDiagram(emitter, "done");

        String output = String.join("\n", emitter.sent);
        assertTrue(sent);
        assertTrue(output.contains("\"type\":\"drawio_done\""));
        assertTrue(output.contains("\"mode\":\"local\""));
        assertTrue(output.contains("API v2"));
    }

    @Test
    public void shouldPersistMergedCanvasAfterLocalPatchMerge() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingCanvasStateStore canvasStateStore = new CapturingCanvasStateStore();
        injectCanvasStateStore(writer, canvasStateStore);
        CapturingEmitter emitter = new CapturingEmitter();
        writer.setCanvasStateContext(emitter, "alice", "diagram-1", 3L);
        writer.setCurrentCanvas(emitter, """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"patch_cells","cells":"<mxCell id='2' value='API v2' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>"}
                """);
        writer.flushPendingDiagram(emitter, "done");

        assertEquals("alice", canvasStateStore.saved.getUserId());
        assertEquals("diagram-1", canvasStateStore.saved.getDiagramId());
        assertEquals(Long.valueOf(3L), canvasStateStore.saved.getVersion());
        assertTrue(canvasStateStore.saved.getCurrentXml().contains("API v2"));
        assertFalse(canvasStateStore.saved.getCurrentXml().contains("value='API'"));
    }

    @Test
    public void shouldRecordTheNumberOfCellsChangedByAPersistedMutation() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingCanvasStateStore canvasStateStore = new CapturingCanvasStateStore();
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        injectCanvasStateStore(writer, canvasStateStore);
        injectTelemetryService(writer, new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CapturingEmitter emitter = new CapturingEmitter();
        writer.setCanvasStateContext(emitter, "alice", "diagram-1", 3L, "aru_cells", "ars_drawing");
        writer.setCurrentCanvas(emitter, """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"drawio_done","content":"<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='API v2' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell><mxCell id='3' value='Worker' vertex='1' parent='1'><mxGeometry x='320' y='100' width='120' height='60' as='geometry'/></mxCell></root></mxGraphModel>"}
                """);
        writer.flushPendingDiagram(emitter, "done");

        assertEquals(1, telemetryStore.diagramSnapshots.size());
        assertEquals(Integer.valueOf(2), telemetryStore.diagramSnapshots.get(0).getChangedCellCount());
    }

    @Test
    public void shouldIncludePersistedCanvasVersionInDrawioDoneChunk() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingCanvasStateStore canvasStateStore = new CapturingCanvasStateStore();
        canvasStateStore.nextVersion = 4L;
        injectCanvasStateStore(writer, canvasStateStore);
        CapturingEmitter emitter = new CapturingEmitter();
        writer.setCanvasStateContext(emitter, "alice", "diagram-1", 3L);

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"drawio_done","content":"<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='API v2' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell></root></mxGraphModel>"}
                """);
        writer.flushPendingDiagram(emitter, "done");

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"drawio_done\""));
        assertTrue(output.contains("\"diagramId\":\"diagram-1\""));
        assertTrue(output.contains("\"version\":4"));
        assertTrue(output.contains("\"contentHash\":\"sha256:saved\""));
        assertTrue(output.contains("\"saveStatus\":\"UPDATED\""));
    }

    @Test
    public void shouldEmitVersionConflictWhenPersistingStaleCanvas() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingCanvasStateStore canvasStateStore = new CapturingCanvasStateStore();
        canvasStateStore.conflict = true;
        canvasStateStore.currentState = CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .version(5L)
                .contentHash("sha256:current")
                .build();
        injectCanvasStateStore(writer, canvasStateStore);
        CapturingEmitter emitter = new CapturingEmitter();
        writer.setCanvasStateContext(emitter, "alice", "diagram-1", 2L);
        writer.setCurrentCanvas(emitter, """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"patch_cells","cells":"<mxCell id='2' value='API v2' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>"}
                """);
        writer.flushPendingDiagram(emitter, "done");

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"version_conflict\""));
        assertTrue(output.contains("\"diagramId\":\"diagram-1\""));
        assertTrue(output.contains("\"expectedVersion\":2"));
        assertTrue(output.contains("\"currentVersion\":5"));
        assertTrue(output.contains("\"currentContentHash\":\"sha256:current\""));
        assertFalse(output.contains("\"type\":\"drawio_done\""));
    }

    @Test
    public void shouldSkipCellReplayWhenEditingExistingCanvas() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();
        writer.setCurrentCanvas(emitter, """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"modify_diagram","xml":"<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='API v2' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell></root></mxGraphModel>"}
                """);
        writer.flushPendingDiagram(emitter, "done");

        String output = String.join("\n", emitter.sent);
        assertFalse(output.contains("\"type\":\"drawio_preview\""));
        assertFalse(output.contains("\"type\":\"drawio_node\""));
        assertTrue(output.contains("\"type\":\"validation_result\""));
        assertTrue(output.contains("\"type\":\"drawio_done\""));
        assertTrue(output.contains("\"mode\":\"local\""));
        assertTrue(output.contains("API v2"));
    }

    @Test
    public void shouldAnimateFirstDrawButNotRepairPassesInTheSameStream() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();

        // First draw on a blank canvas keeps the cell-by-cell animation.
        writer.processAndSendLine(emitter, "drawing", """
                {"type":"create_diagram","xml":"<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell><mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='320' y='100' width='120' height='60' as='geometry'/></mxCell></root></mxGraphModel>"}
                """);
        String firstDraw = String.join("\n", emitter.sent);
        assertEquals(2, countOccurrences(firstDraw, "\"type\":\"drawio_node\""));
        assertEquals(0, countOccurrences(firstDraw, "\"type\":\"drawio_done\""));

        // A repair pass must not replay cells over the finished canvas; it merges in place.
        writer.processAndSendLine(emitter, "drawing", """
                {"type":"optimize_diagram","xml":"<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell><mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='320' y='200' width='120' height='60' as='geometry'/></mxCell></root></mxGraphModel>"}
                """);
        writer.flushPendingDiagram(emitter, "done");

        String output = String.join("\n", emitter.sent);
        assertEquals(2, countOccurrences(output, "\"type\":\"drawio_node\""));
        assertEquals(1, countOccurrences(output, "\"type\":\"drawio_done\""));
        assertEquals(1, countOccurrences(output, "\"mode\":\"local\""));
    }

    @Test
    public void shouldPersistAndEmitOnlyTheLatestCandidateWhenTheRunIsFinalized() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        List<CanvasState> saves = new ArrayList<>();
        injectCanvasStateStore(writer, new ICanvasStateStore() {
            @Override
            public Optional<CanvasState> find(String userId, String diagramId) {
                return Optional.empty();
            }

            @Override
            public CanvasState save(CanvasState state) {
                saves.add(state);
                state.setVersion(4L);
                state.setContentHash("sha256:latest");
                return state;
            }
        });
        CapturingEmitter emitter = new CapturingEmitter();
        writer.setCanvasStateContext(emitter, "alice", "diagram-1", 3L);
        writer.setCurrentCanvas(emitter, """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/></root></mxGraphModel>
                """);

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"drawio_done","content":"<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='Draft' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell></root></mxGraphModel>"}
                """);
        writer.processAndSendLine(emitter, "drawing", """
                {"type":"drawio_done","content":"<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='Final' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell></root></mxGraphModel>"}
                """);

        assertTrue(saves.isEmpty());
        assertEquals(0, countOccurrences(String.join("\n", emitter.sent), "\"type\":\"drawio_done\""));

        writer.flushPendingDiagram(emitter, "done");

        String output = String.join("\n", emitter.sent);
        assertEquals(1, saves.size());
        assertTrue(saves.get(0).getCurrentXml().contains("value='Final'"));
        assertFalse(saves.get(0).getCurrentXml().contains("value='Draft'"));
        assertEquals(1, countOccurrences(output, "\"type\":\"drawio_done\""));
        assertTrue(output.contains("\"contentHash\":\"sha256:latest\""));
    }

    @Test
    public void shouldStopStreamAfterFatalValidationParseFailure() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();

        boolean shouldComplete = writer.processAndSendLine(emitter, "drawing", """
                {"type":"validation_result","valid":false,"severity":"critical","issues":["The Draw.io XML could not be parsed: Element type \\"exitX\\" must be followed by either attribute specifications, \\">\\" or \\"/>\\"."],"content":"The Draw.io XML could not be parsed"}
                """);

        String output = String.join("\n", emitter.sent);
        assertTrue(shouldComplete);
        assertTrue(output.contains("\"type\":\"validation_result\""));
        assertTrue(output.contains("exitX"));
    }

    private static class CapturingEmitter extends ResponseBodyEmitter {
        private final List<String> sent = new ArrayList<>();

        @Override
        public void send(Object object) throws IOException {
            sent.add(String.valueOf(object));
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = text.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }

    private void injectCanvasStateStore(DrawioStreamResponseWriter writer, ICanvasStateStore canvasStateStore) throws Exception {
        Field field = DrawioStreamResponseWriter.class.getDeclaredField("canvasStateStore");
        field.setAccessible(true);
        field.set(writer, canvasStateStore);
    }

    private void injectTelemetryService(DrawioStreamResponseWriter writer,
                                        AgentUsageTelemetryService telemetryService) throws Exception {
        Field field = DrawioStreamResponseWriter.class.getDeclaredField("agentUsageTelemetryService");
        field.setAccessible(true);
        field.set(writer, telemetryService);
    }

    @Test
    public void shouldPersistTheLatestCandidateAgainstTheOriginalExpectedVersion() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        List<Long> savedInputVersions = new ArrayList<>();
        injectCanvasStateStore(writer, new ICanvasStateStore() {
            @Override
            public Optional<CanvasState> find(String userId, String diagramId) {
                return Optional.empty();
            }

            @Override
            public CanvasState save(CanvasState state) {
                savedInputVersions.add(state.getVersion());
                state.setVersion((state.getVersion() == null ? 0L : state.getVersion()) + 1L);
                return state;
            }
        });
        CapturingEmitter emitter = new CapturingEmitter();
        writer.setCanvasStateContext(emitter, "alice", "diagram-1", 3L);
        writer.setCurrentCanvas(emitter, """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"patch_cells","cells":"<mxCell id='2' value='API v2' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>"}
                """);
        writer.processAndSendLine(emitter, "drawing", """
                {"type":"patch_cells","cells":"<mxCell id='2' value='API v3' vertex='1' parent='1'><mxGeometry x='100' y='100' width='160' height='60' as='geometry'/></mxCell>"}
                """);

        assertTrue(savedInputVersions.isEmpty());
        writer.flushPendingDiagram(emitter, "done");

        assertEquals(List.of(3L), savedInputVersions);
        assertFalse("no spurious version conflict for a single stream's own edits",
                String.join("\n", emitter.sent).contains("version_conflict"));
    }

    private static class CapturingCanvasStateStore implements ICanvasStateStore {

        private CanvasState saved;
        private CanvasState currentState;
        private Long nextVersion;
        private boolean conflict;

        @Override
        public Optional<CanvasState> find(String userId, String diagramId) {
            return Optional.ofNullable(currentState);
        }

        @Override
        public CanvasState save(CanvasState state) {
            if (conflict) {
                throw new CanvasStateVersionConflictException(state.getUserId(), state.getDiagramId(), state.getVersion());
            }
            this.saved = state;
            state.setVersion(nextVersion == null ? state.getVersion() : nextVersion);
            state.setContentHash("sha256:saved");
            return state;
        }
    }
}
