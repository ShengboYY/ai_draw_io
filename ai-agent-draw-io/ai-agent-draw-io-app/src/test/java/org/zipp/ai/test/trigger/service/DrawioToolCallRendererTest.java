package org.zipp.ai.test.trigger.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;
import org.zipp.ai.trigger.http.service.DrawioToolCallRenderer;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DrawioToolCallRendererTest {

    @Test
    public void shouldRenderDisplayDiagramXmlAsStreamingChunks() {
        DrawioToolCallRenderer renderer = new DrawioToolCallRenderer();
        JSONObject toolCall = JSON.parseObject("""
                {
                  "type": "display_diagram",
                  "xml": "<mxCell id='2' value='User' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell><mxCell id='3' value='calls' edge='1' source='2' target='2' parent='1'><mxGeometry relative='1' as='geometry'/></mxCell>"
                }
                """);

        List<JSONObject> chunks = renderer.render(toolCall);

        assertEquals(5, chunks.size());
        assertEquals("drawio_preview", chunks.get(0).getString("type"));
        assertEquals("drawio_node", chunks.get(1).getString("type"));
        assertEquals("drawio_edge", chunks.get(2).getString("type"));
        assertEquals("validation_result", chunks.get(3).getString("type"));
        assertEquals("drawio_done", chunks.get(4).getString("type"));
        assertEquals("2", chunks.get(1).getString("id"));
        assertEquals("3", chunks.get(2).getString("id"));

        String xml = chunks.get(4).getString("content");
        assertTrue("the renderer must preserve the raw candidate for the final Gate",
                !xml.contains("<mxGraphModel>"));
        assertTrue(xml.contains("value='User'"));
        assertTrue(xml.contains("value='calls'"));
    }

    @Test
    public void shouldSanitizeRawLabelCharactersBeforeStreamingValidation() {
        DrawioToolCallRenderer renderer = new DrawioToolCallRenderer();
        JSONObject toolCall = JSON.parseObject("""
                {
                  "type": "display_diagram",
                  "xml": "<mxCell id='2' value='<heap & metaspace>' vertex='1' parent='1'><mxGeometry x='100' y='100' width='180' height='70' as='geometry'/></mxCell>"
                }
                """);

        List<JSONObject> chunks = renderer.render(toolCall);

        assertEquals("validation_result", chunks.get(2).getString("type"));
        assertEquals(true, chunks.get(2).getBooleanValue("valid"));
        assertEquals("drawio_done", chunks.get(3).getString("type"));
        assertTrue("preview validation may sanitize, but drawio_done must retain the raw candidate",
                chunks.get(3).getString("content").contains("value='<heap & metaspace>'"));
    }

    @Test
    public void shouldStreamCompleteGraphModelFromEditTool() {
        DrawioToolCallRenderer renderer = new DrawioToolCallRenderer();
        JSONObject toolCall = JSON.parseObject("""
                {
                  "type": "edit_diagram",
                  "xml": "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='API' vertex='1' parent='1'/></root></mxGraphModel>"
                }
                """);

        List<JSONObject> chunks = renderer.render(toolCall);

        assertEquals("drawio_preview", chunks.get(0).getString("type"));
        assertEquals("drawio_node", chunks.get(1).getString("type"));
        assertEquals("validation_result", chunks.get(2).getString("type"));
        assertEquals("drawio_done", chunks.get(3).getString("type"));
        assertEquals("<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='API' vertex='1' parent='1'/></root></mxGraphModel>",
                chunks.get(3).getString("content"));
    }

    @Test
    public void shouldStreamUpdatedCellsToolAsDiagramUpdate() {
        DrawioToolCallRenderer renderer = new DrawioToolCallRenderer();
        JSONObject toolCall = JSON.parseObject("""
                {
                  "type": "update_cells",
                  "xml": "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='API v2' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell></root></mxGraphModel>"
                }
                """);

        List<JSONObject> chunks = renderer.render(toolCall);

        assertEquals("drawio_preview", chunks.get(0).getString("type"));
        assertEquals("drawio_node", chunks.get(1).getString("type"));
        assertEquals("validation_result", chunks.get(2).getString("type"));
        assertEquals("drawio_done", chunks.get(3).getString("type"));
        assertEquals("2", chunks.get(1).getString("id"));
        assertTrue(chunks.get(3).getString("content").contains("API v2"));
    }

    @Test
    public void shouldStreamRouteEdgesToolAsDiagramUpdate() {
        DrawioToolCallRenderer renderer = new DrawioToolCallRenderer();
        JSONObject toolCall = JSON.parseObject("""
                {
                  "type": "route_edges",
                  "xml": "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell><mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='320' y='100' width='120' height='60' as='geometry'/></mxCell><mxCell id='4' value='calls' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell></root></mxGraphModel>"
                }
                """);

        List<JSONObject> chunks = renderer.render(toolCall);

        assertEquals("drawio_preview", chunks.get(0).getString("type"));
        assertEquals("drawio_node", chunks.get(1).getString("type"));
        assertEquals("drawio_node", chunks.get(2).getString("type"));
        assertEquals("drawio_edge", chunks.get(3).getString("type"));
        assertEquals("validation_result", chunks.get(4).getString("type"));
        assertEquals("drawio_done", chunks.get(5).getString("type"));
    }

    @Test
    public void shouldStreamFinalContinueDiagramAsDiagramUpdate() {
        DrawioToolCallRenderer renderer = new DrawioToolCallRenderer();
        JSONObject toolCall = JSON.parseObject("""
                {
                  "type": "continue_diagram",
                  "xml": "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='Long' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell></root></mxGraphModel>"
                }
                """);

        List<JSONObject> chunks = renderer.render(toolCall);

        assertEquals("drawio_preview", chunks.get(0).getString("type"));
        assertEquals("drawio_node", chunks.get(1).getString("type"));
        assertEquals("validation_result", chunks.get(2).getString("type"));
        assertEquals("drawio_done", chunks.get(3).getString("type"));
        assertTrue(chunks.get(3).getString("content").contains("value='Long'"));
    }

    @Test
    public void shouldKeepContainerCellsInPreviewSkeleton() {
        DrawioToolCallRenderer renderer = new DrawioToolCallRenderer();
        JSONObject toolCall = JSON.parseObject("""
                {
                  "type": "display_diagram",
                  "xml": "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='boundary' value='Boundary' style='rounded=1;container=1;verticalAlign=top;' vertex='1' parent='1'/><mxCell id='child' value='Child' vertex='1' parent='1'/></root></mxGraphModel>"
                }
                """);

        List<JSONObject> chunks = renderer.render(toolCall);

        assertTrue(chunks.get(0).getString("content").contains("id='boundary'"));
        assertEquals("drawio_node", chunks.get(1).getString("type"));
        assertEquals("child", chunks.get(1).getString("id"));
        assertEquals("validation_result", chunks.get(2).getString("type"));
        assertEquals("drawio_done", chunks.get(3).getString("type"));
    }

    @Test
    public void shouldTagOptimizeDiagramResultForInPlaceMerge() {
        DrawioToolCallRenderer renderer = new DrawioToolCallRenderer();
        JSONObject toolCall = JSON.parseObject("""
                {
                  "type": "optimize_diagram",
                  "xml": "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell></root></mxGraphModel>"
                }
                """);

        List<JSONObject> chunks = renderer.render(toolCall);

        JSONObject done = chunks.get(chunks.size() - 1);
        assertEquals("drawio_done", done.getString("type"));
        assertEquals("local", done.getString("mode"));
    }

    @Test
    public void shouldIgnoreUnsupportedToolType() {
        DrawioToolCallRenderer renderer = new DrawioToolCallRenderer();
        JSONObject toolCall = JSON.parseObject("""
                {
                  "type": "get_shape_library",
                  "query": "aws lambda"
                }
                """);

        assertTrue(renderer.render(toolCall).isEmpty());
    }
}
