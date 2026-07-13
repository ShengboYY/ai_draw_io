package org.zipp.ai.domain.agent.service.evaluation.intake;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Builds a minimal semantic view and removes source identifiers before the model boundary. */
final class SemanticTraceProjector {
    private static final Pattern PRODUCTION_ID = Pattern.compile("(?i)\\b(?:run|aru|usr|ecc|adt|req|diagram)[_-][A-Za-z0-9_-]+\\b");
    private final EvalDraftSanitizer sanitizer = new EvalDraftSanitizer();
    private final ObjectMapper mapper = new ObjectMapper();

    Projection project(AgentRunDetail detail, List<DebugTraceCapture> captures) {
        if (detail == null || detail.getRun() == null) return new Projection(false, null, List.of("run_unavailable"));
        AgentRunTelemetry run = detail.getRun();
        List<String> rawContents = captures == null ? List.of() : captures.stream()
                .map(DebugTraceCapture::getContent).filter(StringUtils::isNotBlank)
                .map(content -> removeIdentifiers(content, detail)).toList();
        EvalDraftSanitizer.Result sanitized = rawContents.isEmpty()
                ? new EvalDraftSanitizer.Result(true, "[NO_DEBUG_CONTENT]", List.of("no_debug_content"))
                : sanitizer.sanitize(rawContents);
        if (!sanitized.safeForModel()) return new Projection(false, null, sanitized.removedCategories());
        try {
            Map<String, Object> projection = new LinkedHashMap<>();
            projection.put("run", values("status", run.getStatus(), "requestType", run.getRequestType(),
                    "latencyMs", run.getLatencyMs(), "stepCount", run.getStepCount(),
                    "llmCallCount", run.getLlmCallCount(), "toolCallCount", run.getToolCallCount()));
            projection.put("steps", detail.getSteps().stream().map(step -> values("phase", step.getPhase(),
                    "status", step.getStatus(), "errorClass", step.getErrorClass(), "latencyMs", step.getLatencyMs())).toList());
            projection.put("llmCalls", detail.getLlmCalls().stream().map(call -> values("phase", call.getPhase(),
                    "provider", call.getProvider(), "model", call.getModel(), "status", call.getStatus(),
                    "errorClass", call.getErrorClass(), "latencyMs", call.getLatencyMs(),
                    "attemptCount", call.getAttemptCount(), "retryCount", call.getRetryCount(),
                    "promptTokens", call.getPromptTokens(), "completionTokens", call.getCompletionTokens())).toList());
            projection.put("toolCalls", detail.getToolCalls().stream().map(call -> values("phase", call.getPhase(),
                    "toolName", call.getToolName(), "status", call.getStatus(),
                    "errorClass", call.getErrorClass(), "latencyMs", call.getLatencyMs())).toList());
            // Trace metadata JSON is excluded because it can contain tool payloads or identifiers.
            projection.put("traceEvents", detail.getTraceEvents().stream().map(event -> values("eventType", event.getEventType(),
                    "phase", event.getPhase(), "status", event.getStatus())).toList());
            projection.put("sanitizedDebugContent", sanitized.content());
            String json = mapper.writeValueAsString(projection);
            if (containsKnownIdentifier(json, detail) || PRODUCTION_ID.matcher(json).find()) {
                return new Projection(false, null, List.of("identifier_remained"));
            }
            return new Projection(true, json, sanitized.removedCategories());
        } catch (Exception e) {
            return new Projection(false, null, List.of("projection_serialization_failed"));
        }
    }

    private Map<String, Object> values(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            if (entries[i + 1] != null) result.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return result;
    }

    private String removeIdentifiers(String content, AgentRunDetail detail) {
        String result = PRODUCTION_ID.matcher(content).replaceAll("[ID]");
        for (String id : knownIdentifiers(detail)) {
            if (StringUtils.isNotBlank(id)) result = result.replace(id, "[ID]");
        }
        return result;
    }

    private boolean containsKnownIdentifier(String content, AgentRunDetail detail) {
        return knownIdentifiers(detail).stream().filter(StringUtils::isNotBlank).anyMatch(content::contains);
    }

    private List<String> knownIdentifiers(AgentRunDetail detail) {
        AgentRunTelemetry run = detail.getRun();
        List<String> ids = new ArrayList<>(List.of(StringUtils.defaultString(run.getId()),
                StringUtils.defaultString(run.getRequestId()), StringUtils.defaultString(run.getDiagramId()),
                StringUtils.defaultString(run.getUserId()), StringUtils.defaultString(run.getSessionId())));
        detail.getSteps().forEach(value -> ids.add(StringUtils.defaultString(value.getId())));
        detail.getLlmCalls().forEach(value -> ids.add(StringUtils.defaultString(value.getId())));
        detail.getToolCalls().forEach(value -> ids.add(StringUtils.defaultString(value.getId())));
        detail.getTraceEvents().forEach(value -> ids.add(StringUtils.defaultString(value.getId())));
        return ids;
    }

    record Projection(boolean safe, String content, List<String> sanitizerEvidence) { }
}
