package org.zipp.ai.trigger.http;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.api.dto.CanvasVisualReviewRequestDTO;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewExecutor;
import org.zipp.ai.trigger.http.service.CanvasVisualReviewOrchestrator;

import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/visual-reviews")
public class CanvasVisualReviewController {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String RUN_ID_HEADER = "X-Agent-Run-Id";
    private static final Pattern CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final CurrentOwnerHttpResolver ownerResolver;
    private final CanvasVisualReviewOrchestrator orchestrator;
    private final CanvasVisualReviewExecutor reviewExecutor;

    @Autowired
    public CanvasVisualReviewController(CurrentOwnerHttpResolver ownerResolver,
                                        CanvasVisualReviewOrchestrator orchestrator,
                                        CanvasVisualReviewExecutor reviewExecutor) {
        this.ownerResolver = ownerResolver;
        this.orchestrator = orchestrator;
        this.reviewExecutor = reviewExecutor;
    }

    @PostMapping("/stream")
    public ResponseBodyEmitter stream(@RequestBody CanvasVisualReviewRequestDTO request,
                                      @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestIdHeader) {
        String requestId = correlationId(StringUtils.defaultIfBlank(requestIdHeader,
                request == null ? null : request.getRequestId()), "req_");
        String runId = correlationId(null, "aru_visual_");
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(2 * 60 * 1000L) {
            @Override
            protected void extendResponse(ServerHttpResponse outputMessage) {
                outputMessage.getHeaders().set("Content-Type", "application/x-ndjson");
                outputMessage.getHeaders().set(REQUEST_ID_HEADER, requestId);
                outputMessage.getHeaders().set(RUN_ID_HEADER, runId);
            }
        };
        if (request == null) {
            emitter.completeWithError(new IllegalArgumentException("Missing visual review request"));
            return emitter;
        }
        String ownerId = ownerResolver.resolveOwnerId(request.getUserId()).orElse(null);
        if (StringUtils.isBlank(ownerId)) {
            emitter.completeWithError(new IllegalArgumentException("Missing or invalid workspace id"));
            return emitter;
        }
        request.setUserId(ownerId);
        request.setRequestId(requestId);
        // Return the emitter immediately; a bounded pool prevents slow reviews consuming shared workers.
        if (!reviewExecutor.executeRequest(() -> orchestrator.stream(ownerId, runId, request, emitter))) {
            emitter.completeWithError(new IllegalStateException("Visual review capacity is currently exhausted"));
        }
        return emitter;
    }

    private String correlationId(String candidate, String prefix) {
        if (StringUtils.isNotBlank(candidate) && CORRELATION_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return prefix + UUID.randomUUID();
    }
}
