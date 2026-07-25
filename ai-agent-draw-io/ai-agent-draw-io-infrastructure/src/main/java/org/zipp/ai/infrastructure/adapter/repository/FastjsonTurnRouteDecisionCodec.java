package org.zipp.ai.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.checkpoint.EncodedTurnRouteDecision;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpoint;
import org.zipp.ai.application.turn.checkpoint.TurnRouteDecisionCodec;
import org.zipp.ai.application.turn.classification.OutputIntent;
import org.zipp.ai.application.turn.classification.SemanticAction;
import org.zipp.ai.application.turn.classification.SemanticIntent;
import org.zipp.ai.application.turn.classification.TargetNeed;
import org.zipp.ai.application.turn.demand.AcceptedSourceDemand;
import org.zipp.ai.application.turn.demand.DemandResolutionCode;
import org.zipp.ai.application.turn.demand.DemandResolutionReason;
import org.zipp.ai.application.turn.demand.NeedsSourceClarification;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandKind;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.PrePlanOutcome;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fastjson adapter for the current closed route decision algebra. */
@Component
public final class FastjsonTurnRouteDecisionCodec implements TurnRouteDecisionCodec {

    private static final String KIND = "TURN_ROUTE_V1";
    private static final int VERSION = 1;
    @Override
    public EncodedTurnRouteDecision encode(TurnRouteDecision decision) {
        if (decision == null) {
            throw new IllegalArgumentException("route decision must not be null");
        }
        JSONObject root = new JSONObject(true);
        root.put("version", VERSION);
        if (decision instanceof TurnRouteDecision.Plain plain) {
            PrePlanOutcome.SourceFreeReady value = plain.value();
            common(root, "PLAIN", value.contextReadSetDigest(), value.inputBindingDigest(),
                    value.lineage());
            root.put("action", value.plan().action().name());
            root.put("instruction", value.plan().instruction());
        } else if (decision instanceof TurnRouteDecision.SourcePlanning sourcePlanning) {
            PrePlanOutcome.SourcePlanningRequired value = sourcePlanning.value();
            common(root, "SOURCE_PLANNING", value.contextReadSetDigest(), value.inputBindingDigest(),
                    value.lineage());
            root.put("action", value.intent().action().name());
            root.put("outputIntent", value.intent().outputIntent().name());
            root.put("targetNeed", value.intent().targetNeed().name());
            root.put("diagramType", value.intent().diagramType());
            root.put("skillName", value.intent().skillName());
            root.put("demandKind", value.accepted().kind().name());
            root.put("attachmentRefs", new JSONArray(value.accepted().attachmentRefs()));
            root.put("relevanceQuery", value.accepted().relevanceQuery() == null
                    ? "" : value.accepted().relevanceQuery());
            root.put("reasons", reasons(value.demand().reasons()));
        } else if (decision instanceof TurnRouteDecision.Clarification clarification) {
            PrePlanOutcome.NeedsClarification value = clarification.value();
            common(root, "CLARIFICATION", value.contextReadSetDigest(), value.inputBindingDigest(),
                    value.lineage());
            root.put("clarificationKind", value.clarification().kind());
            root.put("reasons", reasons(value.clarification().reasons()));
        } else if (decision instanceof TurnRouteDecision.Unsupported unsupported) {
            PrePlanOutcome.Unsupported value = unsupported.value();
            common(root, "UNSUPPORTED", value.contextReadSetDigest(), value.inputBindingDigest(),
                    value.lineage());
            root.put("code", value.code());
        } else {
            PrePlanOutcome.Unavailable value = ((TurnRouteDecision.Unavailable) decision).value();
            common(root, "UNAVAILABLE", value.contextReadSetDigest(), value.inputBindingDigest(),
                    value.lineage());
            root.put("code", value.code());
            root.put("retryAfterMillis", value.retryAfter().toMillis());
        }
        return new EncodedTurnRouteDecision(KIND, JSON.toJSONString(root));
    }

    @Override
    public TurnRouteDecision decode(TurnDecisionCheckpoint checkpoint) {
        if (checkpoint == null || !KIND.equals(checkpoint.decisionKind())) {
            throw new IllegalArgumentException("unknown route decision checkpoint kind");
        }
        JSONObject root = JSON.parseObject(checkpoint.decisionJson());
        if (root == null || root.getIntValue("version") != VERSION
                || !checkpoint.contextReadSetDigest().equals(root.getString("contextReadSetDigest"))
                || !checkpoint.inputBindingDigest().equals(root.getString("inputBindingDigest"))) {
            throw new IllegalArgumentException("route decision checkpoint binding is invalid");
        }
        String route = text(root, "route", 32);
        PlanningLineageFingerprint lineage = new PlanningLineageFingerprint(text(root, "lineage", 64));
        String contextDigest = text(root, "contextReadSetDigest", 64);
        String inputDigest = text(root, "inputBindingDigest", 64);
        return switch (route) {
            case "PLAIN" -> decodePlain(root, lineage, contextDigest, inputDigest);
            case "SOURCE_PLANNING" -> decodeSourcePlanning(root, lineage, contextDigest, inputDigest);
            case "CLARIFICATION" -> decodeClarification(root, lineage, contextDigest, inputDigest);
            case "UNSUPPORTED" -> new TurnRouteDecision.Unsupported(new PrePlanOutcome.Unsupported(
                    text(root, "code", 128), lineage, contextDigest, inputDigest));
            case "UNAVAILABLE" -> new TurnRouteDecision.Unavailable(new PrePlanOutcome.Unavailable(
                    text(root, "code", 128), duration(root), lineage, contextDigest, inputDigest));
            default -> throw new IllegalArgumentException("unknown route decision");
        };
    }

    private TurnRouteDecision decodePlain(
            JSONObject root,
            PlanningLineageFingerprint lineage,
            String contextDigest,
            String inputDigest
    ) {
        return new TurnRouteDecision.Plain(new PrePlanOutcome.SourceFreeReady(
                new PlainDrawPlan(PlainDrawAction.valueOf(text(root, "action", 32)),
                        text(root, "instruction", 16_000)),
                lineage, contextDigest, inputDigest));
    }

    private TurnRouteDecision decodeSourcePlanning(
            JSONObject root,
            PlanningLineageFingerprint lineage,
            String contextDigest,
            String inputDigest
    ) {
        SemanticIntent intent = new SemanticIntent(
                SemanticAction.valueOf(text(root, "action", 32)),
                OutputIntent.valueOf(text(root, "outputIntent", 32)),
                TargetNeed.valueOf(text(root, "targetNeed", 32)),
                text(root, "diagramType", 128),
                text(root, "skillName", 256));
        List<String> attachmentRefs = strings(root.getJSONArray("attachmentRefs"), 16, 256);
        String relevanceQuery = text(root, "relevanceQuery", 1_000);
        AcceptedSourceDemand accepted = new AcceptedSourceDemand(
                SourceDemandKind.valueOf(text(root, "demandKind", 64)),
                attachmentRefs,
                relevanceQuery.isBlank() ? null : relevanceQuery);
        ResolvedSourceDemand demand = new ResolvedSourceDemand(accepted, reasons(root));
        return new TurnRouteDecision.SourcePlanning(new PrePlanOutcome.SourcePlanningRequired(
                intent, demand, accepted, lineage, contextDigest, inputDigest));
    }

    private TurnRouteDecision decodeClarification(
            JSONObject root,
            PlanningLineageFingerprint lineage,
            String contextDigest,
            String inputDigest
    ) {
        NeedsSourceClarification clarification = new NeedsSourceClarification(
                text(root, "clarificationKind", 128), reasons(root));
        return new TurnRouteDecision.Clarification(new PrePlanOutcome.NeedsClarification(
                clarification, lineage, contextDigest, inputDigest));
    }

    private void common(
            JSONObject root,
            String route,
            String contextDigest,
            String inputDigest,
            PlanningLineageFingerprint lineage
    ) {
        root.put("route", route);
        root.put("contextReadSetDigest", contextDigest);
        root.put("inputBindingDigest", inputDigest);
        root.put("lineage", lineage.value());
    }

    private JSONArray reasons(List<DemandResolutionReason> values) {
        JSONArray result = new JSONArray();
        for (DemandResolutionReason value : values) {
            JSONObject reason = new JSONObject(true);
            reason.put("ruleNumber", value.ruleNumber());
            reason.put("code", value.code().name());
            result.add(reason);
        }
        return result;
    }

    private List<DemandResolutionReason> reasons(JSONObject root) {
        JSONArray values = root.getJSONArray("reasons");
        if (values == null || values.size() > 32) {
            throw new IllegalArgumentException("decision reasons are invalid");
        }
        List<DemandResolutionReason> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof JSONObject reason)
                    || !reason.keySet().equals(Set.of("ruleNumber", "code"))) {
                throw new IllegalArgumentException("decision reason fields are invalid");
            }
            result.add(new DemandResolutionReason(
                    reason.getIntValue("ruleNumber"),
                    DemandResolutionCode.valueOf(text(reason, "code", 64))));
        }
        return List.copyOf(result);
    }

    private List<String> strings(JSONArray values, int countLimit, int itemLimit) {
        if (values == null || values.size() > countLimit) {
            throw new IllegalArgumentException("decision string list is invalid");
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank() || text.length() > itemLimit
                    || !unique.add(text)) {
                throw new IllegalArgumentException("decision string list is invalid");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private Duration duration(JSONObject root) {
        long millis = root.getLongValue("retryAfterMillis");
        if (millis < 0 || millis > Duration.ofDays(1).toMillis()) {
            throw new IllegalArgumentException("decision retry duration is invalid");
        }
        return Duration.ofMillis(millis);
    }

    private String text(JSONObject root, String field, int limit) {
        String value = root.getString(field);
        if (value == null || value.length() > limit) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }
}
