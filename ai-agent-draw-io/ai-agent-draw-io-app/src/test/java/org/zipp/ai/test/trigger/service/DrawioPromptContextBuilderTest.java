package org.zipp.ai.test.trigger.service;

import org.junit.Test;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.canvas.DefaultDrawioCanvasSnapshotService;
import org.zipp.ai.trigger.http.service.DrawioPromptContextBuilder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DrawioPromptContextBuilderTest {

    @Test
    public void shouldOmitExistingCanvasDetailsForCreateNewTasks() {
        DrawioPromptContextBuilder builder = new DrawioPromptContextBuilder(new DefaultDrawioCanvasSnapshotService());
        ChatRequestDTO requestDTO = request("重新画一个登录流程图");
        requestDTO.setCanvasSummary("The canvas contains 2 nodes and 1 edges. Main labels: API, Gateway.");

        String message = builder.buildDrawingContextMessage(requestDTO, routing("create_new"));

        assertTrue(message.contains("[Canvas Context]\nExisting canvas omitted because taskType=create_new."));
        assertTrue(message.contains("[User Request]\n重新画一个登录流程图"));
        assertFalse(message.contains("<mxGraphModel"));
        assertFalse(message.contains("value=\"API\""));
        assertFalse(message.contains("Main labels: API"));
    }

    @Test
    public void shouldUseTargetCellsForPatchTasks() {
        DrawioPromptContextBuilder builder = new DrawioPromptContextBuilder(new DefaultDrawioCanvasSnapshotService());

        String message = builder.buildDrawingContextMessage(request("把 API 改成 Gateway API"), routing("patch_existing"));

        assertTrue(message.contains("[Patch Target Cells]"));
        assertTrue(message.contains("node id=api label=\"API\""));
        assertTrue(message.contains("[User Request]\n把 API 改成 Gateway API"));
        assertFalse(message.contains("node id=gateway label=\"Gateway\""));
        assertFalse(message.contains("edge id=edge1 source=api target=gateway"));
        assertFalse(message.contains("<mxGraphModel"));
        assertFalse(message.contains("value=\"API\""));
    }

    @Test
    public void shouldFallbackToCompactCanvasSnapshotWhenPatchTargetIsUnclear() {
        DrawioPromptContextBuilder builder = new DrawioPromptContextBuilder(new DefaultDrawioCanvasSnapshotService());

        String message = builder.buildDrawingContextMessage(request("把入口节点颜色改成蓝色"), routing("patch_existing"));

        assertTrue(message.contains("[Compact Canvas Snapshot]"));
        assertTrue(message.contains("No patch target cells matched the request; compact snapshot follows."));
        assertTrue(message.contains("node id=api label=\"API\""));
        assertTrue(message.contains("node id=gateway label=\"Gateway\""));
        assertTrue(message.contains("edge id=edge1 source=api target=gateway"));
        assertFalse(message.contains("<mxGraphModel"));
    }

    @Test
    public void shouldKeepFullXmlForLayoutOptimizationTasks() {
        DrawioPromptContextBuilder builder = new DrawioPromptContextBuilder(new DefaultDrawioCanvasSnapshotService());

        String message = builder.buildDrawingContextMessage(request("优化布局"), routing("optimize_layout"));

        assertTrue(message.contains("[Context: Current Draw.io XML]"));
        assertTrue(message.contains("<mxGraphModel"));
        assertTrue(message.contains("value=\"API\""));
        assertTrue(message.contains("[User Request]\n优化布局"));
    }

    private ChatRequestDTO request(String message) {
        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setMessage(message);
        requestDTO.setCanvasXml("<mxGraphModel><root>"
                + "<mxCell id=\"0\"/>"
                + "<mxCell id=\"1\" parent=\"0\"/>"
                + "<mxCell id=\"api\" value=\"API\" vertex=\"1\" parent=\"1\" style=\"rounded=1;whiteSpace=wrap;html=1;\">"
                + "<mxGeometry x=\"120\" y=\"80\" width=\"100\" height=\"40\" as=\"geometry\"/>"
                + "</mxCell>"
                + "<mxCell id=\"gateway\" value=\"Gateway\" vertex=\"1\" parent=\"1\">"
                + "<mxGeometry x=\"280\" y=\"80\" width=\"120\" height=\"40\" as=\"geometry\"/>"
                + "</mxCell>"
                + "<mxCell id=\"edge1\" edge=\"1\" parent=\"1\" source=\"api\" target=\"gateway\">"
                + "<mxGeometry relative=\"1\" as=\"geometry\"/>"
                + "</mxCell>"
                + "</root></mxGraphModel>");
        return requestDTO;
    }

    private IntentRoutingResult routing(String taskType) {
        IntentRoutingResult result = IntentRoutingResult.fallbackDrawAction("test");
        result.setTaskType(taskType);
        return result;
    }

}
