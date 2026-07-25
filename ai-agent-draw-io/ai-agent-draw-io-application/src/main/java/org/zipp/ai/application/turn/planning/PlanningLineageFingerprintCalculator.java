package org.zipp.ai.application.turn.planning;

import org.zipp.ai.application.turn.classification.SemanticIntent;
import org.zipp.ai.application.turn.classification.TurnClassification;
import org.zipp.ai.application.turn.demand.AcceptedSourceDemand;
import org.zipp.ai.application.turn.demand.AmbiguousSourceDemand;
import org.zipp.ai.application.turn.demand.DemandResolutionReason;
import org.zipp.ai.application.turn.demand.NoSourceDemand;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandDecision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes the deterministic pre-probe lineage without reading any source system. */
public final class PlanningLineageFingerprintCalculator {

    private PlanningLineageFingerprintCalculator() {
    }

    public static PlanningLineageFingerprint calculate(
            TurnClassification classification,
            String contextReadSetDigest,
            String inputBindingDigest
    ) {
        if (classification == null) {
            throw new IllegalArgumentException("classification must not be null");
        }
        PlanningContractValues.digest(contextReadSetDigest, "contextReadSetDigest");
        PlanningContractValues.digest(inputBindingDigest, "inputBindingDigest");
        SemanticIntent intent = classification.intent();
        StringBuilder canonical = new StringBuilder();
        field(canonical, "contextReadSetDigest", contextReadSetDigest);
        field(canonical, "inputBindingDigest", inputBindingDigest);
        field(canonical, "instructionDigest", classification.instruction().digest());
        field(canonical, "action", intent.action().name());
        field(canonical, "outputIntent", intent.outputIntent().name());
        field(canonical, "targetNeed", intent.targetNeed().name());
        field(canonical, "diagramType", intent.diagramType());
        field(canonical, "skillName", intent.skillName());
        field(canonical, "decision", decisionValue(classification.demandResolution()));
        if (classification.demandResolution() instanceof ResolvedSourceDemand resolved) {
            for (DemandResolutionReason reason : resolved.reasons()) {
                field(canonical, "reason", reason.ruleNumber() + ":" + reason.code().name());
            }
        }
        return new PlanningLineageFingerprint(sha256(canonical.toString()));
    }

    private static String decisionValue(org.zipp.ai.application.turn.demand.SourceDemandResolution resolution) {
        if (!(resolution instanceof ResolvedSourceDemand resolved)) {
            return resolution.getClass().getSimpleName();
        }
        SourceDemandDecision decision = resolved.decision();
        if (decision instanceof NoSourceDemand) {
            return "NO_SOURCE";
        }
        if (decision instanceof AcceptedSourceDemand accepted) {
            return "ACCEPTED:" + accepted.kind().name()
                    + ":refs=" + String.join(",", accepted.attachmentRefs())
                    + ":query=" + String.valueOf(accepted.relevanceQuery());
        }
        return "AMBIGUOUS:" + ((AmbiguousSourceDemand) decision).reason();
    }

    private static void field(StringBuilder canonical, String name, String value) {
        String normalized = value == null ? "" : value;
        canonical.append(name).append('=').append(normalized.length()).append(':')
                .append(normalized).append('\n');
    }

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
