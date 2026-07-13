package org.zipp.ai.test.domain.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTargetMigrationStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalAdminRole;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseSourceType;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseVersion;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopy;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopyStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalDatasetClass;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalEpisodeStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalGateOutcome;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalRunMode;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalRunStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvaluationProfileSnapshot;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvaluationProfileVersion;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceFindingStatusGroup;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceFindingView;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceRecommendation;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalControlPlaneErrorCode;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EvalControlPlaneContractsTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void workingCopyMustRoundTripTheExistingEvalCaseContract() throws Exception {
        EvalCaseDefinition definition = EvalCaseDefinition.builder()
                .caseId("manual-case-001")
                .caseVersion("1")
                .datasetVersion("dev-draft")
                .privacy(new EvalCaseDefinition.Privacy("synthetic", "manual-v1"))
                .build();
        EvalCaseWorkingCopy original = EvalCaseWorkingCopy.builder()
                .id("ecw_001")
                .caseId("manual-case-001")
                .caseVersion("1")
                .sourceType(EvalCaseSourceType.MANUAL)
                .status(EvalCaseWorkingCopyStatus.DRAFT)
                .ownerUserId("admin-1")
                .revision(1L)
                .definition(definition)
                .createdAt(Instant.parse("2026-07-13T00:00:00Z"))
                .updatedAt(Instant.parse("2026-07-13T00:00:00Z"))
                .build();

        EvalCaseWorkingCopy restored = mapper.readValue(mapper.writeValueAsBytes(original), EvalCaseWorkingCopy.class);

        assertEquals(original.getId(), restored.getId());
        assertEquals(EvalCaseSourceType.MANUAL, restored.getSourceType());
        assertEquals("manual-case-001", restored.getDefinition().getCaseId());
        assertEquals("synthetic", restored.getDefinition().getPrivacy().getClassification());
        assertEquals(Long.valueOf(1L), restored.getRevision());
    }

    @Test
    public void publishedCaseVersionMustBeImmutableAndContainNoProductionLinkage() {
        EvalCaseVersion version = EvalCaseVersion.builder()
                .caseId("manual-case-001")
                .caseVersion("1")
                .contentHash("sha256:abc")
                .artifactRef("eval-cases/sha256-abc.yaml")
                .approvedBy("reviewer-1")
                .publishedAt(Instant.parse("2026-07-13T01:00:00Z"))
                .build();

        assertEquals("sha256:abc", version.getContentHash());
        assertNotNull(version.getPublishedAt());
        assertFalse(fieldNames(EvalCaseVersion.class).contains("sourceRunId"));
        assertFalse(fieldNames(EvalCaseVersion.class).contains("candidateId"));
        assertFalse(Arrays.stream(EvalCaseVersion.class.getMethods())
                .anyMatch(method -> method.getName().startsWith("set")));
    }

    @Test
    public void lifecycleEnumsMustKeepFailureAvailabilityAndDecisionStatesSeparate() {
        assertTrue(Set.of(EvalEpisodeStatus.values()).containsAll(
                Set.of(EvalEpisodeStatus.PASS, EvalEpisodeStatus.FAIL,
                        EvalEpisodeStatus.ERROR, EvalEpisodeStatus.UNAVAILABLE)));
        assertTrue(Set.of(EvalGateOutcome.values()).containsAll(
                Set.of(EvalGateOutcome.PASS, EvalGateOutcome.BLOCK, EvalGateOutcome.NO_DECISION)));
        assertTrue(Set.of(EvalRunStatus.values()).containsAll(
                Set.of(EvalRunStatus.CREATED, EvalRunStatus.QUEUED, EvalRunStatus.RUNNING,
                        EvalRunStatus.COMPLETED, EvalRunStatus.CANCELLED, EvalRunStatus.INFRA_ERROR)));
        assertTrue(Set.of(EvalRunMode.values()).containsAll(
                Set.of(EvalRunMode.MODE_B, EvalRunMode.MODE_C, EvalRunMode.RELEASE)));
        assertTrue(Set.of(EvalDatasetClass.values()).containsAll(
                Set.of(EvalDatasetClass.DEV, EvalDatasetClass.CORE, EvalDatasetClass.SEQUESTERED)));
        assertTrue(Set.of(EvalAdminRole.values()).containsAll(
                Set.of(EvalAdminRole.EDITOR, EvalAdminRole.REVIEWER,
                        EvalAdminRole.ADMIN, EvalAdminRole.RELEASE_OWNER)));
    }

    @Test
    public void evaluationTargetAndProfileContractsMustBeStableAndSerializable() throws Exception {
        assertEquals(Set.of(EvaluationTarget.FULL_AGENT, EvaluationTarget.INTENT_ROUTER,
                EvaluationTarget.DRAWING_QUALITY), Set.of(EvaluationTarget.values()));
        assertEquals(Set.of(EvaluationTargetMigrationStatus.INFERRED,
                        EvaluationTargetMigrationStatus.AMBIGUOUS,
                        EvaluationTargetMigrationStatus.CONFIRMED),
                Set.of(EvaluationTargetMigrationStatus.values()));
        assertTrue(Set.of(EvalControlPlaneErrorCode.values())
                .contains(EvalControlPlaneErrorCode.PROFILE_CASE_CONFLICT));

        EvaluationProfileVersion version = new EvaluationProfileVersion(
                "router-live", "3", EvaluationTarget.INTENT_ROUTER,
                "router_live", EvalRunMode.MODE_C, 5, true,
                "{\"metrics\":[\"accuracy\",\"macro_f1\"]}");
        EvaluationProfileSnapshot snapshot = new EvaluationProfileSnapshot(
                version.profileId(), version.version(), version.target(), version.runnerAdapter(),
                version.mode(), version.repetitions(), version.gateEligible(),
                version.configJson(), "sha256:profile-v3", false);

        EvaluationProfileSnapshot restored = mapper.readValue(
                mapper.writeValueAsBytes(snapshot), EvaluationProfileSnapshot.class);
        assertEquals(snapshot, restored);
        assertEquals(EvaluationTarget.INTENT_ROUTER, restored.target());
        assertEquals("sha256:profile-v3", restored.configHash());
    }

    @Test
    public void traceFindingViewMustRemainAReadOnlyProjectionOfCandidateStatus() {
        assertEquals(TraceFindingStatusGroup.NEW, TraceFindingStatusGroup.from(EvalCandidateStatus.DETECTED));
        assertEquals(TraceFindingStatusGroup.TRIAGED, TraceFindingStatusGroup.from(EvalCandidateStatus.TRIAGED));
        assertEquals(TraceFindingStatusGroup.DRAFTING, TraceFindingStatusGroup.from(EvalCandidateStatus.DRAFT_READY));
        assertEquals(TraceFindingStatusGroup.DRAFTING,
                TraceFindingStatusGroup.from(EvalCandidateStatus.NEEDS_MANUAL_RECONSTRUCTION));
        assertEquals(TraceFindingStatusGroup.IN_REVIEW,
                TraceFindingStatusGroup.from(EvalCandidateStatus.UNDER_REVIEW));
        assertEquals(TraceFindingStatusGroup.APPROVED, TraceFindingStatusGroup.from(EvalCandidateStatus.APPROVED));
        assertEquals(TraceFindingStatusGroup.DISMISSED, TraceFindingStatusGroup.from(EvalCandidateStatus.REJECTED));
        assertEquals(TraceFindingStatusGroup.DISMISSED, TraceFindingStatusGroup.from(EvalCandidateStatus.EXPIRED));
        assertEquals(TraceFindingStatusGroup.DISMISSED, TraceFindingStatusGroup.from(EvalCandidateStatus.PURGED));
        assertEquals(TraceFindingStatusGroup.PROMOTED, TraceFindingStatusGroup.from(EvalCandidateStatus.PUBLISHED));

        TraceFindingView view = new TraceFindingView(
                "ecc_001", "run_001", "edit_existing", "drawing-agent", 1200L,
                "LLM", "semantic-v2", "false_success", "high", 0.91,
                "The assistant reported success without a canvas mutation.", List.of("canvas_hash_unchanged"),
                List.of("trace://run/run_001"), new TraceRecommendation("tool", "canvas hash unchanged",
                "Persist the mutation", "Inspect the edit result path.", 1, 0.91), EvalCandidateStatus.TRIAGED,
                Instant.parse("2026-07-13T02:00:00Z"), "admin-1", Instant.parse("2026-07-13T02:05:00Z"));

        assertEquals(EvalCandidateStatus.TRIAGED, view.candidateStatus());
        assertEquals(TraceFindingStatusGroup.TRIAGED, view.statusGroup());
        assertFalse(Arrays.stream(TraceFindingView.class.getMethods())
                .anyMatch(method -> method.getName().startsWith("set")));
    }

    @Test
    public void legacyCaseExecutionProfileMustStayCompatibleButDeprecatedUntilMigration() throws Exception {
        Field field = EvalCaseDefinition.class.getDeclaredField("executionProfile");
        assertTrue(field.isAnnotationPresent(Deprecated.class));
        assertTrue(EvalCaseDefinition.ExecutionProfile.class.isAnnotationPresent(Deprecated.class));
        assertFalse(fieldNames(EvalCaseDefinition.class).contains("evaluationProfile"));
    }

    private Set<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(Field::getName).collect(Collectors.toSet());
    }
}
