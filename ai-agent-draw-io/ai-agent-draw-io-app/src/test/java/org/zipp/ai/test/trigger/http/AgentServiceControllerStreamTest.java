package org.zipp.ai.test.trigger.http;

import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.trigger.http.AgentServiceController;
import org.zipp.ai.trigger.http.CurrentOwnerHttpResolver;
import org.zipp.ai.trigger.http.turn.TurnV2ProductIngressAdapter;
import org.zipp.ai.trigger.http.turn.TurnV2ProductStreamExecutor;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AgentServiceControllerStreamTest {

    @Test(timeout = 3_000L)
    public void shouldReturnEmitterBeforeV2IngressFinishes() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        CurrentOwnerHttpResolver owners = mock(CurrentOwnerHttpResolver.class);
        when(owners.resolveOwnerId(any())).thenReturn(Optional.of("usr_stream"));
        TurnV2ProductIngressAdapter ingress = mock(TurnV2ProductIngressAdapter.class);
        TurnV2ProductStreamExecutor executor = new TurnV2ProductStreamExecutor(1, 1);
        CountDownLatch ingressStarted = new CountDownLatch(1);
        CountDownLatch releaseIngress = new CountDownLatch(1);
        CountDownLatch ingressFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            ingressStarted.countDown();
            try {
                // A synchronous controller would block here until the latch times out.
                releaseIngress.await(2, TimeUnit.SECONDS);
            } finally {
                ingressFinished.countDown();
            }
            return null;
        }).when(ingress).stream(any(), any(), any(), any(), any());
        inject(controller, "currentOwnerHttpResolver", owners);
        inject(controller, "turnV2ProductIngressAdapter", ingress);
        inject(controller, "turnV2ProductStreamExecutor", executor);

        ChatRequestDTO request = new ChatRequestDTO();
        request.setUserId("ignored");
        request.setDiagramId("diagram-1");
        request.setMessage("draw a JVM architecture");

        try {
            long started = System.nanoTime();
            ResponseBodyEmitter emitter = controller.chatStream(request, "request-stream-1");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertNotNull(emitter);
            assertTrue(ingressStarted.await(1, TimeUnit.SECONDS));
            assertTrue("controller must return before the ingress terminal wait",
                    elapsedMillis < 500L);
        } finally {
            releaseIngress.countDown();
            assertTrue(ingressFinished.await(1, TimeUnit.SECONDS));
            executor.close();
        }
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
