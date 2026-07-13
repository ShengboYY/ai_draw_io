package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalHarnessResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.ModeBReplayExecutionFactory;
import org.zipp.ai.domain.agent.service.evaluation.EvalCaseLoader;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.Assert.*;

public class EvalCaseLifecycleServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T03:00:00Z");
    private final WorkingStore workingStore = new WorkingStore();
    private final EvidenceStore evidenceStore = new EvidenceStore();
    private final ReviewStore reviewStore = new ReviewStore();
    private final EvalCaseWorkingCopyService workingService = new EvalCaseWorkingCopyService(
            workingStore, new EmptyTraceStore(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final EvalCaseValidationService validationService = new EvalCaseValidationService(
            workingService, evidenceStore, Clock.fixed(NOW, ZoneOffset.UTC));
    private final EvalCaseDryRunService dryRunService = new EvalCaseDryRunService(
            workingService, evidenceStore, new ModeBReplayExecutionFactory("candidate-sha"),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final EvalCaseReviewService reviewService = new EvalCaseReviewService(
            workingService, reviewStore, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    public void validImportedCaseCanValidateDryRunAndEnterReview() throws Exception {
        EvalCaseWorkingCopy workingCopy = workingService.createImportedYaml(validYaml(), "editor-1", EvalAdminRole.EDITOR);

        EvalCaseValidationResult validation = validationService.validate(workingCopy.getId(),
                "editor-1", EvalAdminRole.EDITOR);
        EvalCaseDryRunResult dryRun = dryRunService.run(workingCopy.getId(),
                "editor-1", EvalAdminRole.EDITOR);
        EvalCaseWorkingCopy underReview = reviewService.submit(workingCopy.getId(),
                "editor-1", EvalAdminRole.EDITOR);

        assertTrue(validation.isPassed());
        assertEquals(EvalHarnessResult.Status.PASS, dryRun.getResult().getStatus());
        assertEquals(EvalCaseWorkingCopyStatus.UNDER_REVIEW, underReview.getStatus());
        assertEquals(2, evidenceStore.values.size());
    }

    @Test
    public void invalidCaseMustPersistValidationEvidenceAndCannotDryRun() {
        EvalCaseWorkingCopy workingCopy = workingService.createManual(EvalCaseDefinition.builder()
                .caseId("invalid").caseVersion("1").datasetVersion("dev-draft")
                .privacy(new EvalCaseDefinition.Privacy("synthetic", "manual-v1")).build(),
                "editor-1", EvalAdminRole.EDITOR);

        EvalCaseValidationResult validation = validationService.validate(workingCopy.getId(),
                "editor-1", EvalAdminRole.EDITOR);

        assertFalse(validation.isPassed());
        assertEquals(EvalCaseWorkingCopyStatus.VALIDATION_FAILED,
                workingService.get(workingCopy.getId(), "editor-1", EvalAdminRole.EDITOR).getStatus());
        assertFalse(validation.getEvidence().isEmpty());
        assertThrows(IllegalStateException.class, () -> dryRunService.run(workingCopy.getId(),
                "editor-1", EvalAdminRole.EDITOR));
    }

    @Test
    public void highRiskCaseRequiresAFourEyesReview() throws Exception {
        EvalCaseWorkingCopy workingCopy = workingService.createImportedYaml(validYaml(), "editor-1", EvalAdminRole.EDITOR);
        validationService.validate(workingCopy.getId(), "editor-1", EvalAdminRole.EDITOR);
        dryRunService.run(workingCopy.getId(), "editor-1", EvalAdminRole.EDITOR);
        reviewService.submit(workingCopy.getId(), "editor-1", EvalAdminRole.EDITOR);

        assertThrows(SecurityException.class, () -> reviewService.decide(workingCopy.getId(), "APPROVE",
                "self approval", "editor-1", EvalAdminRole.ADMIN));

        EvalCaseWorkingCopy approved = reviewService.decide(workingCopy.getId(), "APPROVE",
                "verified assertions", "reviewer-2", EvalAdminRole.REVIEWER);
        assertEquals(EvalCaseWorkingCopyStatus.APPROVED, approved.getStatus());
        assertEquals("APPROVE", reviewStore.values.get(0).getDecision());
    }

    @Test
    public void dryRunMustUseEachCasesOwnRecordedReplayAcrossAgentRoutes() throws Exception {
        for (String resource : List.of("answer-greeting.yaml", "create-customer-er.yaml", "edit-api-gateway.yaml")) {
            EvalCaseWorkingCopy workingCopy = workingService.createImportedYaml(resourceYaml(resource),
                    "editor-1", EvalAdminRole.EDITOR);
            assertTrue(resource, validationService.validate(workingCopy.getId(), "editor-1", EvalAdminRole.EDITOR).isPassed());
            EvalCaseDryRunResult result = dryRunService.run(workingCopy.getId(), "editor-1", EvalAdminRole.EDITOR);
            assertEquals(resource, EvalHarnessResult.Status.PASS, result.getResult().getStatus());
            assertNotNull(resource, result.getTrace());
        }
    }

    @Test
    public void validationMustExplainVersionReplayPrivacyAndGraphFailures() throws Exception {
        List<EvalCaseDefinition> invalid = new ArrayList<>();
        EvalCaseDefinition version = validDefinition(); version.setFixtureVersion("fixture-v0"); invalid.add(version);
        EvalCaseDefinition replay = validDefinition(); replay.setReplay(null); invalid.add(replay);
        EvalCaseDefinition privacy = validDefinition(); privacy.setPrivacy(new EvalCaseDefinition.Privacy("production", "none")); invalid.add(privacy);
        EvalCaseDefinition leakage = validDefinition(); leakage.getInput().put("sourceRunId", "run-secret"); invalid.add(leakage);
        EvalCaseDefinition graph = validDefinition();
        graph.getExpected().setGraph(EvalCaseDefinition.GraphAssertions.builder().requiredNodes(List.of("Gateway")).build());
        invalid.add(graph);

        for (EvalCaseDefinition definition : invalid) {
            EvalCaseWorkingCopy workingCopy = workingService.createManual(definition, "editor-1", EvalAdminRole.EDITOR);
            EvalCaseValidationResult result = validationService.validate(workingCopy.getId(), "editor-1", EvalAdminRole.EDITOR);
            assertFalse(definition.getCaseId(), result.isPassed());
            assertFalse(definition.getCaseId(), result.getEvidence().isEmpty());
        }
    }

    private String validYaml() throws Exception {
        return resourceYaml("edit-api-gateway.yaml");
    }

    private String resourceYaml(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/evals/core-v1/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private EvalCaseDefinition validDefinition() throws Exception {
        return new EvalCaseLoader().load(new ByteArrayInputStream(validYaml().getBytes(StandardCharsets.UTF_8)));
    }

    private static final class WorkingStore implements IEvalCaseWorkingCopyStore {
        private final Map<String, EvalCaseWorkingCopy> values = new LinkedHashMap<>();
        @Override public Optional<EvalCaseWorkingCopy> find(String id) { return Optional.ofNullable(values.get(id)); }
        @Override public List<EvalCaseWorkingCopy> list(EvalCaseWorkingCopyStatus status, String owner, int limit, int offset) { return values.values().stream().skip(offset).limit(limit).toList(); }
        @Override public void insert(EvalCaseWorkingCopy value) { values.put(value.getId(), value); }
        @Override public boolean update(EvalCaseWorkingCopy value, long expectedRevision) {
            EvalCaseWorkingCopy current = values.get(value.getId());
            if (current == null || current.getRevision() != expectedRevision) return false;
            values.put(value.getId(), value); return true;
        }
    }

    private static final class EvidenceStore implements IEvalCaseEvidenceStore {
        private final List<EvalCaseEvidence> values = new ArrayList<>();
        @Override public void insert(EvalCaseEvidence evidence) { values.add(evidence); }
        @Override public List<EvalCaseEvidence> list(String workingCopyId) { return values.stream().filter(v -> workingCopyId.equals(v.getWorkingCopyId())).toList(); }
    }

    private static final class ReviewStore implements IEvalCaseWorkingCopyReviewStore {
        private final List<EvalCaseWorkingCopyReview> values = new ArrayList<>();
        @Override public void insert(EvalCaseWorkingCopyReview review) { values.add(review); }
        @Override public List<EvalCaseWorkingCopyReview> list(String workingCopyId) { return values.stream().filter(v -> workingCopyId.equals(v.getWorkingCopyId())).toList(); }
    }

    private static final class EmptyTraceStore implements ITraceToEvalStore {
        @Override public Optional<org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate> findCandidate(String candidateId) { return Optional.empty(); }
        @Override public Optional<org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String sourceRunId, String failureFamily) { return Optional.empty(); }
        @Override public void insertCandidate(org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate candidate) { }
        @Override public void updateCandidateStatus(String candidateId, org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus status) { }
        @Override public void insertReview(org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseReview review) { }
        @Override public void insertLineage(org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseLineage lineage) { }
    }
}
