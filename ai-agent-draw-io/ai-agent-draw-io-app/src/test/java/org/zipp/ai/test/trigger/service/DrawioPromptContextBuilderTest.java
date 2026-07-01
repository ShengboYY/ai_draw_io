package org.zipp.ai.test.trigger.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Test;
import org.slf4j.LoggerFactory;
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
    public void shouldKeepFullXmlForEditExistingTasks() {
        DrawioPromptContextBuilder builder = new DrawioPromptContextBuilder(new DefaultDrawioCanvasSnapshotService());

        String message = builder.buildDrawingContextMessage(request("把 API 改成 Gateway API"), routing("edit_existing"));

        assertTrue(message.contains("[Context: Current Draw.io XML]"));
        assertTrue(message.contains("<mxGraphModel"));
        assertTrue(message.contains("value=\"API\""));
        assertTrue(message.contains("[User Request]\n把 API 改成 Gateway API"));
    }

    @Test
    public void shouldInjectCompactCanvasIssuesForEditExistingTasks() {
        DrawioPromptContextBuilder builder = new DrawioPromptContextBuilder(new DefaultDrawioCanvasSnapshotService());

        String message = builder.buildDrawingContextMessage(overlappingRequest("整理一下重叠节点"), routing("edit_existing"));

        assertTrue(message.contains("[Canvas Issues]"));
        assertTrue(message.contains("valid=false"));
        assertTrue(message.contains("severity=major"));
        assertTrue(message.contains("type=NODE_OVERLAP"));
        assertTrue(message.contains("targets=api,gateway"));
        assertTrue(message.contains("repairability=candidate"));
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

    @Test
    public void shouldLogDrawingContextShapeWithoutRawXml() {
        DrawioPromptContextBuilder builder = new DrawioPromptContextBuilder(new DefaultDrawioCanvasSnapshotService());
        Logger logger = (Logger) LoggerFactory.getLogger(DrawioPromptContextBuilder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            builder.buildDrawingContextMessage(request("把 API 改成 Gateway API"), routing("edit_existing"));

            assertTrue(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("[draw-context]")
                            && message.contains("taskType=edit_existing")
                            && message.contains("contextType=full_xml")
                            && message.contains("hasCanvas=true")
                            && !message.contains("<mxGraphModel")));
        } finally {
            logger.detachAppender(appender);
        }
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

    private ChatRequestDTO overlappingRequest(String message) {
        ChatRequestDTO requestDTO = request(message);
        requestDTO.setCanvasXml("<mxGraphModel><root>"
                + "<mxCell id=\"0\"/>"
                + "<mxCell id=\"1\" parent=\"0\"/>"
                + "<mxCell id=\"api\" value=\"API\" vertex=\"1\" parent=\"1\">"
                + "<mxGeometry x=\"120\" y=\"80\" width=\"120\" height=\"60\" as=\"geometry\"/>"
                + "</mxCell>"
                + "<mxCell id=\"gateway\" value=\"Gateway\" vertex=\"1\" parent=\"1\">"
                + "<mxGeometry x=\"160\" y=\"100\" width=\"120\" height=\"60\" as=\"geometry\"/>"
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
