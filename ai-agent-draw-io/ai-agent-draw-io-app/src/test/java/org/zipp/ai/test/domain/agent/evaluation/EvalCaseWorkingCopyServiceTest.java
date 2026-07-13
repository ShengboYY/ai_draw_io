package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalAdminRole;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseSourceType;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopy;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopyStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseDraft;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalCaseWorkingCopyService;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalCaseWorkingCopyStore;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EvalCaseWorkingCopyServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-13T02:00:00Z");
    private final InMemoryWorkingCopyStore store = new InMemoryWorkingCopyStore();
    private final InMemoryTraceStore traceStore = new InMemoryTraceStore();
    private final EvalCaseWorkingCopyService service = new EvalCaseWorkingCopyService(
            store, traceStore, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    public void editorCanCreateAndUpdateAnOwnedManualWorkingCopyWithOptimisticRevision() {
        EvalCaseWorkingCopy created = service.createManual(definition("manual-001", "1", "hello"),
                "editor-1", EvalAdminRole.EDITOR);
        EvalCaseDefinition changed = definition("manual-001", "1", "updated");

        EvalCaseWorkingCopy updated = service.update(created.getId(), 1L, changed,
                "editor-1", EvalAdminRole.EDITOR);

        assertEquals(EvalCaseSourceType.MANUAL, created.getSourceType());
        assertEquals(EvalCaseWorkingCopyStatus.DRAFT, created.getStatus());
        assertEquals(Long.valueOf(2L), updated.getRevision());
        assertEquals("updated", updated.getDefinition().getInput().get("user"));
        assertThrows(IllegalStateException.class, () -> service.update(created.getId(), 1L, changed,
                "editor-1", EvalAdminRole.EDITOR));
    }

    @Test
    public void anotherEditorCannotOverwriteAnOwnedDraftButAdminCan() {
        EvalCaseWorkingCopy created = service.createManual(definition("manual-002", "1", "hello"),
                "editor-1", EvalAdminRole.EDITOR);

        assertThrows(SecurityException.class, () -> service.update(created.getId(), 1L,
                definition("manual-002", "1", "other"), "editor-2", EvalAdminRole.EDITOR));

        EvalCaseWorkingCopy updated = service.update(created.getId(), 1L,
                definition("manual-002", "1", "admin"), "admin-1", EvalAdminRole.ADMIN);
        assertEquals("admin", updated.getDefinition().getInput().get("user"));
    }

    @Test
    public void workingCopyCloneMustStartANewDraftWithoutCandidateLinkage() {
        EvalCaseWorkingCopy source = service.createManual(definition("source", "1", "hello"),
                "editor-1", EvalAdminRole.EDITOR);
        source.setCandidateId("ecc_short_lived");

        EvalCaseWorkingCopy clone = service.cloneWorkingCopy(source.getId(), "clone", "1",
                "editor-1", EvalAdminRole.EDITOR);

        assertEquals(EvalCaseSourceType.WORKING_COPY_CLONE, clone.getSourceType());
        assertEquals(EvalCaseWorkingCopyStatus.DRAFT, clone.getStatus());
        assertEquals(Long.valueOf(1L), clone.getRevision());
        assertEquals("clone", clone.getDefinition().getCaseId());
        assertNull(clone.getCandidateId());
    }

    @Test
    public void yamlImportMustUseTheCanonicalEvalCaseLoader() throws Exception {
        String yaml;
        try (var input = getClass().getResourceAsStream("/evals/core-v1/edit-api-gateway.yaml")) {
            yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        EvalCaseWorkingCopy imported = service.createImportedYaml(yaml, "editor-1", EvalAdminRole.EDITOR);

        assertEquals(EvalCaseSourceType.IMPORTED, imported.getSourceType());
        assertEquals("edit-api-gateway-001", imported.getDefinition().getCaseId());
        assertEquals("fixture-v1", imported.getDefinition().getFixtureVersion());
    }

    @Test
    public void traceDraftBecomesSyntheticCaseWithoutProductionIdentifiers() {
        traceStore.draft = EvalCaseDraft.builder()
                .id("ecd_1")
                .candidateId("ecc_1")
                .failureSummary("assistant reported unable to load")
                .suspectedFailureFamily("false_success")
                .userTurns(List.of("Rename API to Gateway"))
                .expectedRoute("edit_existing")
                .suggestedAssertions(List.of("canvas must change"))
                .sanitizerVersion("eval-sanitizer-v2")
                .modelVersion("draft-model-v1")
                .needsHumanReview(true)
                .createdAt(NOW)
                .build();

        EvalCaseWorkingCopy created = service.createFromTraceDraft("ecc_1", "trace-case", "1",
                "editor-1", EvalAdminRole.EDITOR);

        assertEquals(EvalCaseSourceType.TRACE_DRAFT, created.getSourceType());
        assertEquals("synthetic", created.getDefinition().getPrivacy().getClassification());
        assertEquals(Boolean.FALSE, created.getDefinition().getProvenance().getSourceTraceRetained());
        assertEquals("edit_existing", created.getDefinition().getExpected().getRouteType());
        assertEquals(List.of("Rename API to Gateway"), created.getDefinition().getInput().get("turns"));
        String serialized = created.getDefinition().toString();
        assertFalse(serialized.contains("run_"));
        assertFalse(serialized.contains("debug"));
    }

    @Test
    public void listMustApplyOwnerStatusAndPaginationAtTheStoreBoundary() {
        service.createManual(definition("one", "1", "one"), "editor-1", EvalAdminRole.EDITOR);
        service.createManual(definition("two", "1", "two"), "editor-2", EvalAdminRole.EDITOR);

        List<EvalCaseWorkingCopy> owned = service.list("DRAFT", "editor-1", 50, 0,
                "editor-1", EvalAdminRole.EDITOR);

        assertEquals(1, owned.size());
        assertEquals("editor-1", owned.get(0).getOwnerUserId());
        assertTrue(store.lastLimit <= 200);
    }

    private EvalCaseDefinition definition(String caseId, String version, String user) {
        return EvalCaseDefinition.builder()
                .caseId(caseId)
                .caseVersion(version)
                .datasetVersion("dev-draft")
                .input(Map.of("user", user))
                .privacy(new EvalCaseDefinition.Privacy("synthetic", "manual-v1"))
                .build();
    }

    private static final class InMemoryWorkingCopyStore implements IEvalCaseWorkingCopyStore {
        private final Map<String, EvalCaseWorkingCopy> values = new LinkedHashMap<>();
        private int lastLimit;

        @Override
        public Optional<EvalCaseWorkingCopy> find(String id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public List<EvalCaseWorkingCopy> list(EvalCaseWorkingCopyStatus status, String ownerUserId,
                                              int limit, int offset) {
            lastLimit = limit;
            return values.values().stream()
                    .filter(value -> status == null || status == value.getStatus())
                    .filter(value -> ownerUserId == null || ownerUserId.equals(value.getOwnerUserId()))
                    .skip(offset).limit(limit).toList();
        }

        @Override
        public void insert(EvalCaseWorkingCopy workingCopy) {
            values.put(workingCopy.getId(), workingCopy);
        }

        @Override
        public boolean update(EvalCaseWorkingCopy workingCopy, long expectedRevision) {
            EvalCaseWorkingCopy existing = values.get(workingCopy.getId());
            if (existing == null || existing.getRevision() != expectedRevision) return false;
            values.put(workingCopy.getId(), workingCopy);
            return true;
        }
    }

    private static final class InMemoryTraceStore implements ITraceToEvalStore {
        private EvalCaseDraft draft;

        @Override
        public Optional<EvalCaseDraft> findLatestDraft(String candidateId) {
            return Optional.ofNullable(draft).filter(value -> candidateId.equals(value.getCandidateId()));
        }

        @Override public Optional<org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate> findCandidate(String candidateId) { return Optional.empty(); }
        @Override public Optional<org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String sourceRunId, String failureFamily) { return Optional.empty(); }
        @Override public void insertCandidate(org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate candidate) { }
        @Override public void updateCandidateStatus(String candidateId, org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus status) { }
        @Override public void insertReview(org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseReview review) { }
        @Override public void insertLineage(org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseLineage lineage) { }
    }
}
