package org.zipp.ai.application.turn.planning;

import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.demand.AcceptedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Probe input built only from the complete pre-plan result. Required commands deliberately expose
 * no fallback accessor.
 */
public sealed interface SourceProbeCommand
        permits SourceProbeCommand.Required, SourceProbeCommand.OptionalDiscovery {

    SourceProbeBinding binding();

    AcceptedSourceDemand demand();

    static SourceProbeCommand from(PrePlanOutcome.SourcePlanningRequired required) {
        if (required == null) {
            throw new IllegalArgumentException("source planning requirement must not be null");
        }
        SourceProbeBinding binding = new SourceProbeBinding(
                required.lineage(), required.contextReadSetDigest(), required.inputBindingDigest());
        if (required.accepted().kind() == SourceDemandKind.CURRENT_MESSAGE_ATTACHMENTS_REQUIRED) {
            return new Required(binding, required.accepted());
        }
        PlainDrawAction action = switch (required.intent().action()) {
            case CREATE -> PlainDrawAction.CREATE;
            case EDIT -> PlainDrawAction.EDIT;
            case LAYOUT -> PlainDrawAction.LAYOUT;
            default -> throw new IllegalArgumentException("OPTIONAL_DISCOVERY_REQUIRES_PLAIN_DRAW");
        };
        PlainDrawPlan plan = new PlainDrawPlan(action, required.instruction().value());
        String branchId = digest(
                binding.lineage().value(), binding.contextReadSetDigest(),
                binding.inputBindingDigest(), action.name(), plan.instruction());
        return new OptionalDiscovery(
                binding, required.accepted(), new ValidatedPlainFallback(branchId, plan));
    }

    record Required(
            SourceProbeBinding binding,
            AcceptedSourceDemand demand
    ) implements SourceProbeCommand {
        public Required {
            if (binding == null || demand == null
                    || demand.kind() != SourceDemandKind.CURRENT_MESSAGE_ATTACHMENTS_REQUIRED) {
                throw new IllegalArgumentException("invalid required Probe command");
            }
        }
    }

    record OptionalDiscovery(
            SourceProbeBinding binding,
            AcceptedSourceDemand demand,
            ValidatedPlainFallback validatedProbeFallback
    ) implements SourceProbeCommand {
        public OptionalDiscovery {
            if (binding == null || demand == null || validatedProbeFallback == null
                    || demand.kind() != SourceDemandKind.OPTIONAL_DISCOVERY
                    || demand.relevanceQuery() == null || demand.relevanceQuery().isBlank()) {
                throw new IllegalArgumentException("invalid Optional Discovery Probe command");
            }
        }
    }

    private static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
