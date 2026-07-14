package org.zipp.ai.test.trigger.http;

import org.junit.After;
import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.api.dto.CanvasVisualReviewRequestDTO;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewExecutor;
import org.zipp.ai.trigger.http.CanvasVisualReviewController;
import org.zipp.ai.trigger.http.CurrentOwnerHttpResolver;
import org.zipp.ai.trigger.http.service.CanvasVisualReviewOrchestrator;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CanvasVisualReviewControllerTest {

    private final CanvasVisualReviewExecutor reviewExecutor = new CanvasVisualReviewExecutor(1, 10, 1, 10);

    @After
    public void closeExecutor() {
        reviewExecutor.close();
    }

    @Test
    public void serverResolvedOwnerOverridesTheRequestBodyOwner() throws Exception {
        CurrentOwnerHttpResolver ownerResolver = new CurrentOwnerHttpResolver() {
            @Override
            public Optional<String> resolveOwnerId(String legacyOwnerId) {
                return Optional.of("usr_server_owner");
            }
        };
        AtomicReference<String> capturedOwner = new AtomicReference<>();
        CountDownLatch invoked = new CountDownLatch(1);
        CanvasVisualReviewOrchestrator orchestrator = new CanvasVisualReviewOrchestrator(
                null, null, null, null, null, null, null) {
            @Override
            public void stream(String ownerId, String visualReviewRunId,
                               CanvasVisualReviewRequestDTO request, ResponseBodyEmitter emitter) {
                capturedOwner.set(ownerId);
                assertEquals("usr_server_owner", request.getUserId());
                assertTrue(visualReviewRunId.startsWith("aru_visual_"));
                invoked.countDown();
                emitter.complete();
            }
        };
        CanvasVisualReviewController controller = new CanvasVisualReviewController(ownerResolver, orchestrator, reviewExecutor);
        CanvasVisualReviewRequestDTO request = new CanvasVisualReviewRequestDTO();
        request.setUserId("usr_attacker_supplied");

        controller.stream(request, "request-1234");

        assertTrue(invoked.await(2, TimeUnit.SECONDS));
        assertEquals("usr_server_owner", capturedOwner.get());
    }
}
