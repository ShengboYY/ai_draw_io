package org.zipp.ai.infrastructure.adapter.repository;

import org.zipp.ai.application.turn.planning.DirectCandidateFact;
import org.zipp.ai.application.turn.planning.DirectCandidateOrigin;
import org.zipp.ai.application.turn.planning.RoleAvailability;
import org.zipp.ai.application.turn.planning.RoleUnavailability;
import org.zipp.ai.application.turn.planning.SourceAvailability;
import org.zipp.ai.application.turn.planning.SourceProbeCommand;
import org.zipp.ai.application.turn.planning.SourceProbeContext;
import org.zipp.ai.application.turn.planning.SourceProbeOutcome;
import org.zipp.ai.application.turn.planning.SourceProbePort;
import org.zipp.ai.application.turn.planning.RetrievalCandidateFact;
import org.zipp.ai.application.turn.planning.SourceRole;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.RequestSourceOrigin;
import org.zipp.ai.domain.retrieval.RequestSourceResolutionCommand;
import org.zipp.ai.domain.retrieval.RequestSourceResolutionService;
import org.zipp.ai.domain.retrieval.ResolvedSource;
import org.zipp.ai.domain.retrieval.ResolvedSourceSet;
import org.zipp.ai.domain.retrieval.SourceMode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/**
 * Owner/scope-fenced Probe adapter. It returns only source identity facts; bytes and prepared
 * evidence remain behind the later preparation port.
 */
public final class MySqlSourceProbeAdapter implements SourceProbePort {

    private final RequestSourceResolutionService resolution;

    public MySqlSourceProbeAdapter(RequestSourceResolutionService resolution) {
        this.resolution = Objects.requireNonNull(resolution, "resolution");
    }

    @Override
    public SourceProbeOutcome probe(SourceProbeCommand command) {
        return new SourceProbeOutcome.Terminal(
                command.binding(), "SOURCE_PROBE_CONTEXT_REQUIRED");
    }

    @Override
    public SourceProbeOutcome probe(SourceProbeCommand command, SourceProbeContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        try {
            CatalogOwner owner = new CatalogOwner(
                    OwnerType.valueOf(context.ownerType()), context.ownerKey());
            ResolvedSourceSet sources = resolution.resolve(new RequestSourceResolutionCommand(
                    owner,
                    context.diagramId(),
                    context.conversationId(),
                    context.runId(),
                    sourceMode(command),
                    command.demand().attachmentRefs(),
                    List.of()));
            if (sources.resolutionFailed()) {
                return new SourceProbeOutcome.Unavailable(
                        command.binding(), SourceProbeOutcome.Unavailability.DEPENDENCY_UNAVAILABLE);
            }
            return new SourceProbeOutcome.Available(command.binding(), availability(command, sources));
        } catch (RuntimeException failure) {
            return new SourceProbeOutcome.Unavailable(
                    command.binding(), SourceProbeOutcome.Unavailability.DEPENDENCY_UNAVAILABLE);
        }
    }

    private SourceAvailability availability(SourceProbeCommand command, ResolvedSourceSet sources) {
        if (command instanceof SourceProbeCommand.Direct) {
            return new SourceAvailability.SingleRole(direct(sources, command));
        }
        if (command instanceof SourceProbeCommand.Required) {
            return new SourceAvailability.SingleRole(retrieval(sources, command));
        }
        if (command instanceof SourceProbeCommand.OptionalDiscovery) {
            return new SourceAvailability.SingleRole(retrieval(sources, command));
        }
        RoleAvailability direct = direct(sources, command);
        RoleAvailability retrieval = retrieval(sources, command);
        return new SourceAvailability.Composite(direct, retrieval);
    }

    private RoleAvailability direct(ResolvedSourceSet sources, SourceProbeCommand command) {
        List<DirectCandidateFact> candidates = sources.sources().stream()
                .filter(ResolvedSource::directReadable)
                .map(source -> new DirectCandidateFact(
                        command.binding(),
                        source.versionId(),
                        origin(source.origin()),
                        fingerprint(source),
                        source.versionId()))
                .toList();
        if (!candidates.isEmpty()) return new RoleAvailability.DirectAvailable(candidates);
        return new RoleAvailability.Unavailable(SourceRole.DIRECT, unavailable(sources));
    }

    private RoleAvailability retrieval(ResolvedSourceSet sources, SourceProbeCommand command) {
        List<RetrievalCandidateFact> candidates = sources.sources().stream()
                .filter(source -> source.hasText() && source.ready())
                .map(source -> new RetrievalCandidateFact(command.binding(), source.versionId()))
                .toList();
        if (!candidates.isEmpty()) return new RoleAvailability.RetrievalAvailable(candidates);
        return new RoleAvailability.Unavailable(SourceRole.RETRIEVAL, unavailable(sources));
    }

    private RoleUnavailability unavailable(ResolvedSourceSet sources) {
        if (sources.processingSourceCount() > 0) return RoleUnavailability.PROCESSING;
        return RoleUnavailability.NO_MATCH;
    }

    private SourceMode sourceMode(SourceProbeCommand command) {
        // Current-message attachment declarations are an exact allow-list. EXPLICIT would also
        // expand older Conversation/Diagram/Chartbook sources and could select a removed draft.
        return command.demand().attachmentRefs().isEmpty()
                ? SourceMode.AUTO
                : SourceMode.EXPLICIT_ONLY;
    }

    private DirectCandidateOrigin origin(RequestSourceOrigin origin) {
        return origin == RequestSourceOrigin.ATTACHMENT
                ? DirectCandidateOrigin.CURRENT_MESSAGE_ATTACHMENT
                : DirectCandidateOrigin.NAMED_SOURCE;
    }

    private String fingerprint(ResolvedSource source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest((source.materialId() + "\u001f"
                    + source.versionId() + "\u001f" + source.revisionId()).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
