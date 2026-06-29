package org.zipp.ai.test.trigger.service;

import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.trigger.http.service.DrawioStreamResponseWriter;
import org.zipp.ai.trigger.http.service.DrawioToolCallRenderer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DrawioStreamResponseWriterTest {

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
        assertTrue(beforeFlush.contains("\"type\":\"drawio_done\""));

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
    public void shouldSendValidDiagramImmediatelyWithoutPendingFlush() throws Exception {
        DrawioStreamResponseWriter writer = new DrawioStreamResponseWriter(new DrawioToolCallRenderer());
        CapturingEmitter emitter = new CapturingEmitter();

        writer.processAndSendLine(emitter, "drawing", """
                {"type":"display_diagram","xml":"<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='80' as='geometry'/></mxCell><mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='320' y='100' width='140' height='80' as='geometry'/></mxCell>"}
                """);

        String beforeFlush = String.join("\n", emitter.sent);
        assertTrue(beforeFlush.contains("\"type\":\"drawio_done\""));

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

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"continuation_result\""));
        assertTrue(output.contains("\"type\":\"drawio_node\""));
        assertTrue(output.contains("value='A'"));
        assertTrue(output.contains("value='B'"));
        assertTrue(output.contains("\"type\":\"drawio_done\""));
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
}
