package org.zipp.ai.test.domain.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
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

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
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

    private Set<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(Field::getName).collect(Collectors.toSet());
    }
}
