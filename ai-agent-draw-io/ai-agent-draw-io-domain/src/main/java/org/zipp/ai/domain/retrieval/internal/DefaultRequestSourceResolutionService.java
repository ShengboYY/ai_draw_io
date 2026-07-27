package org.zipp.ai.domain.retrieval.internal;

import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.retrieval.*;
import org.zipp.ai.domain.retrieval.port.RequestSourceResolutionPort;
import org.zipp.ai.domain.retrieval.port.RequestSourceSnapshotStore;
import org.zipp.ai.domain.retrieval.port.SourceResolutionCandidate;

import java.util.*;

/** Resolves raw IDs once, fixes exact revisions and makes retries replay the persisted run snapshot. */
public final class DefaultRequestSourceResolutionService implements RequestSourceResolutionService {
    private static final int AUTOMATIC_SOURCE_LIMIT = 80;

    private final RequestSourceResolutionPort catalog;
    private final RequestSourceSnapshotStore snapshots;

    public DefaultRequestSourceResolutionService(RequestSourceResolutionPort catalog,
                                                 RequestSourceSnapshotStore snapshots) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @Override
    public ResolvedSourceSet resolve(RequestSourceResolutionCommand command) {
        Objects.requireNonNull(command, "command");
        String fingerprint = command.declarationFingerprint();
        Optional<RequestSourceSnapshotStore.StoredSnapshot> stored =
                snapshots.find(command.owner(), command.runId());
        if (stored.isPresent()) return replay(stored.get(), fingerprint);

        ResolutionAccumulator accumulator = new ResolutionAccumulator();
        List<SourceResolutionCandidate> attachments = command.attachmentUploadIds().isEmpty()
                ? List.of() : catalog.resolveAttachments(command);
        accumulator.addDeclared(command.attachmentUploadIds(), attachments, RequestSourceOrigin.ATTACHMENT);

        List<SourceResolutionCandidate> explicit = command.selectedVersionIds().isEmpty()
                ? List.of() : catalog.resolveExplicitVersions(command);
        accumulator.addDeclared(command.selectedVersionIds(), explicit, RequestSourceOrigin.EXPLICIT);

        if (command.sourceMode() == SourceMode.AUTO || command.sourceMode() == SourceMode.EXPLICIT) {
            accumulator.addAutomatic(catalog.resolveAutomatic(command, AUTOMATIC_SOURCE_LIMIT));
            // Persist the authoritative Conversation upload count so probe and Evidence see one snapshot.
            accumulator.addProcessing(catalog.countPendingConversationUploads(command));
        }

        ResolvedSourceSet resolved = accumulator.result(command.sourceMode());
        snapshots.save(command.owner(), command.runId(), fingerprint, resolved);
        return snapshots.find(command.owner(), command.runId())
                .map(snapshot -> replay(snapshot, fingerprint))
                .orElseThrow(() -> new IllegalStateException("SOURCE_SNAPSHOT_NOT_PERSISTED"));
    }

    private ResolvedSourceSet replay(RequestSourceSnapshotStore.StoredSnapshot stored, String fingerprint) {
        if (!Objects.equals(stored.declarationFingerprint(), fingerprint)) {
            throw new IllegalStateException("SOURCE_SNAPSHOT_DECLARATION_CONFLICT");
        }
        return stored.sources();
    }

    private static final class ResolutionAccumulator {
        private final LinkedHashMap<String, ResolvedSource> sources = new LinkedHashMap<>();
        private int processing;
        private int unavailable;

        private void addDeclared(List<String> declarationIds, List<SourceResolutionCandidate> candidates,
                                 RequestSourceOrigin origin) {
            Set<String> found = new HashSet<>();
            for (SourceResolutionCandidate candidate : candidates) {
                found.add(candidate.declarationId());
                boolean contributesProcessing = !candidate.ready() && candidate.processing();
                if (candidate.ready() || candidate.directReadable()) {
                    add(candidate, origin, contributesProcessing);
                }
                if (contributesProcessing) {
                    processing++;
                } else if (!candidate.ready() && !candidate.directReadable()) {
                    unavailable++;
                }
            }
            unavailable += (int) declarationIds.stream().filter(id -> !found.contains(id)).count();
        }

        private void addAutomatic(List<SourceResolutionCandidate> candidates) {
            // AUTO is scoped to the active Conversation, Diagram, and Chartbook, never the whole Library.
            candidates.stream().filter(candidate -> candidate.ready() || candidate.directReadable())
                    .filter(candidate -> candidate.scopeType() != MaterialScopeType.LIBRARY).forEach(candidate ->
                    add(candidate, candidate.pinned() ? RequestSourceOrigin.PINNED
                            : RequestSourceOrigin.AUTOMATIC, false));
        }

        private void addProcessing(int count) {
            processing += Math.max(0, count);
        }

        private void add(SourceResolutionCandidate candidate, RequestSourceOrigin origin,
                         boolean countsAsProcessingSource) {
            sources.putIfAbsent(candidate.versionId(), new ResolvedSource(candidate.materialId(),
                    candidate.versionId(), candidate.revisionId(), candidate.kind(), candidate.displayName(),
                    candidate.scopeType(), candidate.scopeKey(), candidate.state(), origin, candidate.hasText(),
                    candidate.hasVisual(), candidate.pinned(), countsAsProcessingSource));
        }

        private ResolvedSourceSet result(SourceMode mode) {
            return new ResolvedSourceSet(mode, List.copyOf(sources.values()), processing, unavailable);
        }
    }
}
