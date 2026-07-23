package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.retrieval.internal.DefaultRequestSourceResolutionService;
import org.zipp.ai.domain.retrieval.port.RequestSourceResolutionPort;
import org.zipp.ai.domain.retrieval.port.RequestSourceSnapshotStore;
import org.zipp.ai.domain.retrieval.port.SourceResolutionCandidate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestSourceResolutionServiceTest {
    private final CatalogOwner owner = new CatalogOwner(OwnerType.USER, "alice");

    @Test
    void fixesTheExactRevisionForEveryConsumerOfOneRun() {
        MutableResolutionPort catalog = new MutableResolutionPort();
        InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
        RequestSourceResolutionService service = new DefaultRequestSourceResolutionService(catalog, snapshots);
        RequestSourceResolutionCommand command = new RequestSourceResolutionCommand(
                owner, "diagram-1", "conversation-1", "run-1",
                SourceMode.EXPLICIT_ONLY, List.of(), List.of("version-1"));
        catalog.explicit = List.of(candidate("version-1", "revision-1"));

        ResolvedSourceSet first = service.resolve(command);
        catalog.explicit = List.of(candidate("version-1", "revision-2"));
        ResolvedSourceSet replay = service.resolve(command);

        assertEquals("revision-1", first.sources().get(0).revisionId());
        assertEquals(first, replay);
        assertEquals(1, catalog.explicitCalls);
    }

    @Test
    void automaticExpansionNeverAddsUnselectedConversationAttachments() {
        MutableResolutionPort catalog = new MutableResolutionPort();
        InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
        RequestSourceResolutionService service = new DefaultRequestSourceResolutionService(catalog, snapshots);
        catalog.attachments = List.of(new SourceResolutionCandidate(
                "upl-selected", "material-selected", "version-selected", "revision-selected",
                "PDF", MaterialScopeType.CONVERSATION, "conversation-1", "READY", "SUCCEEDED",
                true, true, false, false));
        catalog.automatic = List.of(
                new SourceResolutionCandidate("version-unselected", "material-unselected",
                        "version-unselected", "revision-unselected", "PDF",
                        MaterialScopeType.CONVERSATION, "conversation-1", "READY", "",
                        true, true, false, false),
                new SourceResolutionCandidate("version-library", "material-library",
                        "version-library", "revision-library", "PDF",
                        MaterialScopeType.LIBRARY, "personal", "READY", "",
                        false, true, false, false));

        ResolvedSourceSet result = service.resolve(new RequestSourceResolutionCommand(
                owner, "diagram-1", "conversation-1", "run-2",
                SourceMode.AUTO, List.of("upl-selected"), List.of()));

        assertEquals(List.of("version-selected", "version-library"),
                result.sources().stream().map(ResolvedSource::versionId).toList());
    }

    @Test
    void reportsProcessingAndMissingDeclarationsWithoutInventingSources() {
        MutableResolutionPort catalog = new MutableResolutionPort();
        RequestSourceResolutionService service =
                new DefaultRequestSourceResolutionService(catalog, new InMemorySnapshotStore());
        catalog.attachments = List.of(new SourceResolutionCandidate(
                "upl-processing", null, null, null, "PDF",
                MaterialScopeType.CONVERSATION, "conversation-1", "PROCESSING", "PROCESSING",
                false, false, false, false));

        ResolvedSourceSet result = service.resolve(new RequestSourceResolutionCommand(
                owner, "diagram-1", "conversation-1", "run-3",
                SourceMode.NONE, List.of("upl-processing", "upl-missing"), List.of()));

        assertEquals(List.of(), result.sources());
        assertEquals(SourceMode.EXPLICIT, result.mode());
        assertEquals(1, result.processingSourceCount());
        assertEquals(1, result.unavailableSourceCount());
    }

    @Test
    void rejectsAChangedDeclarationWhenTheSameRunIsRetried() {
        MutableResolutionPort catalog = new MutableResolutionPort();
        RequestSourceResolutionService service =
                new DefaultRequestSourceResolutionService(catalog, new InMemorySnapshotStore());
        catalog.explicit = List.of(candidate("version-1", "revision-1"));
        service.resolve(new RequestSourceResolutionCommand(
                owner, "diagram-1", "conversation-1", "run-4",
                SourceMode.EXPLICIT_ONLY, List.of(), List.of("version-1")));

        IllegalStateException conflict = assertThrows(IllegalStateException.class, () ->
                service.resolve(new RequestSourceResolutionCommand(
                        owner, "diagram-1", "conversation-1", "run-4",
                        SourceMode.EXPLICIT_ONLY, List.of(), List.of("version-2"))));

        assertEquals("SOURCE_SNAPSHOT_DECLARATION_CONFLICT", conflict.getMessage());
    }

    private SourceResolutionCandidate candidate(String versionId, String revisionId) {
        return new SourceResolutionCandidate(versionId, "material-1", versionId, revisionId,
                "PDF", MaterialScopeType.LIBRARY, "personal", "READY", "SUCCEEDED",
                false, true, false, false);
    }

    private static final class MutableResolutionPort implements RequestSourceResolutionPort {
        private List<SourceResolutionCandidate> explicit = List.of();
        private List<SourceResolutionCandidate> attachments = List.of();
        private List<SourceResolutionCandidate> automatic = List.of();
        private int explicitCalls;

        @Override
        public List<SourceResolutionCandidate> resolveAttachments(RequestSourceResolutionCommand command) {
            return attachments;
        }

        @Override
        public List<SourceResolutionCandidate> resolveExplicitVersions(RequestSourceResolutionCommand command) {
            explicitCalls++;
            return explicit;
        }

        @Override
        public List<SourceResolutionCandidate> resolveAutomatic(RequestSourceResolutionCommand command, int limit) {
            return automatic;
        }
    }

    private static final class InMemorySnapshotStore implements RequestSourceSnapshotStore {
        private final Map<String, StoredSnapshot> values = new HashMap<>();

        @Override
        public Optional<StoredSnapshot> find(CatalogOwner owner, String runId) {
            return Optional.ofNullable(values.get(runId));
        }

        @Override
        public void save(CatalogOwner owner, String runId, String declarationFingerprint,
                         ResolvedSourceSet sources) {
            values.putIfAbsent(runId, new StoredSnapshot(declarationFingerprint, sources));
        }
    }
}
