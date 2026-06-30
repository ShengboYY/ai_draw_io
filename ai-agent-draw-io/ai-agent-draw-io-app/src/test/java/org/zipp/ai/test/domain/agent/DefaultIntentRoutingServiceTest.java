package org.zipp.ai.test.domain.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.intent.DefaultIntentRoutingService;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DefaultIntentRoutingServiceTest {

    @Test
    public void shouldUseStructuredCanvasXmlForFastPatchRoute() throws Exception {
        IntentRoutingCommand command = IntentRoutingCommand.builder()
                .message("把 API 改成 Gateway")
                .canvasXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"API\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>")
                .build();

        IntentRoutingResult result = tryFastPatchRoute(command);

        assertEquals("draw_action", result.getIntent());
        assertEquals("edit_existing", result.getDrawMode());
        assertEquals("edit_existing", result.getTaskType());
    }

    @Test
    public void shouldNotUseFastPatchRouteForCreateRequestsWithExistingCanvas() throws Exception {
        IntentRoutingCommand command = IntentRoutingCommand.builder()
                .message("重新画一个用户登录流程图")
                .canvasXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"API\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>")
                .build();

        assertNull(tryFastPatchRoute(command));
    }

    @Test
    public void shouldLogHighLevelFastPathRoutingDecision() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultIntentRoutingService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            IntentRoutingCommand command = IntentRoutingCommand.builder()
                    .userId("alice")
                    .message("把 API 改成 Gateway")
                    .canvasXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"API\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>")
                    .build();

            new DefaultIntentRoutingService().route(command);

            assertEquals(1, appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("[intent-route] source=fast_path"))
                    .filter(event -> event.getFormattedMessage().contains("userId=alice"))
                    .filter(event -> event.getFormattedMessage().contains("drawMode=edit_existing"))
                    .filter(event -> event.getFormattedMessage().contains("taskType=edit_existing"))
                    .count());
        } finally {
            logger.detachAppender(appender);
        }
    }

    private IntentRoutingResult tryFastPatchRoute(IntentRoutingCommand command) throws Exception {
        // Keep the production method private while still locking the structured fast-path behavior.
        Method method = DefaultIntentRoutingService.class.getDeclaredMethod("tryFastPatchRoute", IntentRoutingCommand.class);
        method.setAccessible(true);
        return (IntentRoutingResult) method.invoke(new DefaultIntentRoutingService(), command);
    }

}
