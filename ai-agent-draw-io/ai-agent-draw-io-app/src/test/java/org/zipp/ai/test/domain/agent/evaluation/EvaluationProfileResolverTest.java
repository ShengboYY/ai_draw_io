package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.*;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class EvaluationProfileResolverTest {
    @Test
    public void builtInsResolveToStableSecretFreeSnapshots() {
        EvaluationProfileResolver resolver = resolver(DefaultEvaluationProfiles.versions());

        assertEquals(7, resolver.list().size());
        EvaluationProfileSnapshot first = resolver.resolve("router-live", "1",
                EvaluationTarget.INTENT_ROUTER, EvalRunMode.MODE_C);
        EvaluationProfileSnapshot second = resolver.resolve("router-live", "1",
                EvaluationTarget.INTENT_ROUTER, EvalRunMode.MODE_C);

        assertEquals(first.canonicalConfigJson(), second.canonicalConfigJson());
        assertEquals(first.configHash(), second.configHash());
        assertEquals(64, first.configHash().length());
        assertTrue(first.canonicalConfigJson().contains("credentialAlias"));
        assertFalse(first.canonicalConfigJson().toLowerCase().contains("apikey"));
    }

    @Test
    public void builtInProfileVersionMustChangeWhenExecutionSemanticsChange() {
        EvaluationProfileResolver resolver = resolver(DefaultEvaluationProfiles.versions());
        // Pin semantics to id@version so a preset edit must publish a new version instead of rewriting history.
        Map<String, String> expected = Map.of(
                "full-agent-smoke@1", "1483b6050bb3e83dd6076d4955c91ea7f6ca943e802d1b3d44960f3e00fd2b09",
                "full-agent-release@1", "fce1e6b3be1ea1236af4f0f0471a586a998e9088a1d065c1e30ed34700abcfa2",
                "router-deterministic@1", "1453792aa206924f7eec67f8ef98e60e476aee6d6e91ae40b1f4adfe9d17d222",
                "router-live@1", "6dd7250a27d1ad7e1ec3f3dfa4baed74d5dd1724e045c33a006a1e1de28410c7",
                "drawing-structure@1", "5a9d32caacc07655f1a7498c0c79d9362644013db61719f0240b9dc73b291435",
                "drawing-visual@1", "d5bec76fbdf7777752f86e6621c08deccbd1a546f34d7de3de75267559261d95",
                "production-visual-review@1", "4c8ee7db86d06a103d6bdd736d27751f63089b74577c8de071682436269b05ff");

        assertEquals(expected.size(), resolver.list().size());
        resolver.list().forEach(profile -> {
            String key = profile.profileId() + "@" + profile.version();
            String actual = resolver.resolve(profile.profileId(), profile.version(),
                    profile.target(), profile.mode()).configHash();
            assertEquals("Execution semantics changed; publish a new Profile version for " + key,
                    expected.get(key), actual);
        });
    }

    @Test
    public void targetModeAndLegacyConflictsAreRejected() {
        EvaluationProfileResolver resolver = resolver(DefaultEvaluationProfiles.versions());
        assertEquals(EvalControlPlaneErrorCode.TARGET_MISMATCH,
                assertThrows(EvalControlPlaneException.class, () -> resolver.resolve("router-live", "1",
                        EvaluationTarget.DRAWING_QUALITY, EvalRunMode.MODE_C)).getCode());

        EvaluationProfileSnapshot snapshot = resolver.resolve("full-agent-smoke", "1",
                EvaluationTarget.FULL_AGENT, EvalRunMode.MODE_B);
        EvalCaseDefinition.ExecutionProfile legacy = new EvalCaseDefinition.ExecutionProfile();
        legacy.setModel("different-model");
        EvalCaseDefinition definition = EvalCaseDefinition.builder().caseId("legacy").caseVersion("1")
                .executionProfile(legacy).build();
        assertEquals(EvalControlPlaneErrorCode.PROFILE_CASE_CONFLICT,
                assertThrows(EvalControlPlaneException.class,
                        () -> resolver.verifyLegacyCompatibility(snapshot, List.of(definition))).getCode());
    }

    @Test
    public void arbitrarySecretFieldsCannotEnterRunSnapshot() {
        EvaluationProfileVersion unsafe = new EvaluationProfileVersion("unsafe", "1",
                EvaluationTarget.FULL_AGENT, "full_agent", EvalRunMode.MODE_B, 1, false,
                "{\"model\":\"stubbed\",\"nested\":{\"accessToken\":\"do-not-store\"}}");

        EvalControlPlaneException failure = assertThrows(EvalControlPlaneException.class,
                () -> resolver(List.of(unsafe)).resolve("unsafe", "1", EvaluationTarget.FULL_AGENT, EvalRunMode.MODE_B));

        assertEquals(EvalControlPlaneErrorCode.VALIDATION_FAILED, failure.getCode());
    }

    @Test
    public void profileOwnsBudgetGatePolicyAndGraderManifest() {
        EvaluationProfileResolver resolver = resolver(DefaultEvaluationProfiles.versions());
        EvaluationProfileSnapshot snapshot = resolver.resolve("full-agent-release", "1",
                EvaluationTarget.FULL_AGENT, EvalRunMode.RELEASE);

        EvaluationProfileResolver.EvaluationProfilePolicy policy = resolver.policy(snapshot);
        assertEquals(10D, policy.maxEstimatedCost(), 0D);
        assertEquals(20, policy.minimumCases());
        assertEquals(20, policy.minimumPairedCases());
        assertTrue(resolver.graderManifestJson(snapshot).contains("response-judge-v1"));
    }

    @Test
    public void historicalRunKeepsSnapshotWhenPresetDefinitionChanges() {
        EvaluationProfileVersion original = DefaultEvaluationProfiles.versions().stream()
                .filter(profile -> profile.profileId().equals("router-live")).findFirst().orElseThrow();
        EvaluationProfileSnapshot stored = resolver(List.of(original)).resolve("router-live", "1",
                EvaluationTarget.INTENT_ROUTER, EvalRunMode.MODE_C);
        EvaluationProfileVersion changedWithoutVersionBump = new EvaluationProfileVersion(original.profileId(),
                original.version(), original.target(), original.runnerAdapter(), original.mode(), original.repetitions(),
                original.gateEligible(), "{\"model\":\"changed\",\"credentialAlias\":\"safe\",\"credentialVersion\":\"2\"}");

        EvaluationProfileSnapshot current = resolver(List.of(changedWithoutVersionBump)).resolve("router-live", "1",
                EvaluationTarget.INTENT_ROUTER, EvalRunMode.MODE_C);

        assertNotEquals(stored.configHash(), current.configHash());
        assertTrue(stored.canonicalConfigJson().contains("gpt-5.5"));
    }

    @Test
    public void legacyCaseConfigBecomesOneImmutableCompatibilitySnapshot() {
        EvaluationProfileResolver resolver = resolver(DefaultEvaluationProfiles.versions());
        EvalCaseDefinition.ExecutionProfile legacy = EvalCaseDefinition.ExecutionProfile.builder()
                .model("legacy-model-v1").temperature(0.3D).promptConfigHash("legacy-prompt")
                .skillCatalogHash("legacy-skills").toolPolicyVersion("legacy-tools")
                .maxReviewIterations(4).inputPricePerMillion(2D).outputPricePerMillion(8D).build();
        EvalCaseDefinition definition = EvalCaseDefinition.builder().caseId("legacy").caseVersion("1")
                .executionProfile(legacy).build();

        EvaluationProfileSnapshot snapshot = resolver.resolveForRun(null, null, EvaluationTarget.FULL_AGENT,
                EvalRunMode.MODE_B, List.of(definition));

        assertEquals("legacy-case", snapshot.profileId());
        assertTrue(snapshot.customized());
        assertTrue(snapshot.canonicalConfigJson().contains("legacy-model-v1"));
        assertTrue(snapshot.canonicalConfigJson().contains("\"maxReviewIterations\":4"));
        assertEquals(64, snapshot.configHash().length());

        EvalRun run = EvalRun.builder().profileId(snapshot.profileId()).profileVersion(snapshot.profileVersion())
                .profileSnapshotJson(snapshot.canonicalConfigJson()).profileConfigHash(snapshot.configHash()).build();
        resolver.materializeExecutionConfig(run, List.of(definition));
        assertEquals("legacy-model-v1", definition.getExecutionProfile().getModel());
        assertEquals(Integer.valueOf(4), definition.getExecutionProfile().getMaxReviewIterations());
        EvalExecution execution = new EvalExecution();
        resolver.applyExecutionMetadata(run, execution);
        assertEquals(snapshot.configHash(), execution.getExecutionProfileHash());
    }

    @Test
    public void liveRuntimeVersionsAreFrozenAndValidated() {
        EvaluationProfileResolver resolver = resolver(DefaultEvaluationProfiles.versions());
        EvaluationProfileSnapshot base = resolver.resolve("full-agent-release", "1",
                EvaluationTarget.FULL_AGENT, EvalRunMode.MODE_C);
        EvalLiveRunReadiness ready = EvalLiveRunReadiness.builder().providerCredentialReady(true)
                .judgeVersion("judge-runtime-v1").calibrationVersion("cal-v1").build();
        EvaluationProfileSnapshot bound = resolver.bindLiveRuntime(base, ready);
        EvalRun run = EvalRun.builder().mode(EvalRunMode.MODE_C).profileSnapshotJson(bound.canonicalConfigJson()).build();

        resolver.verifyLiveRuntime(run, ready);
        EvalLiveRunReadiness changed = EvalLiveRunReadiness.builder().providerCredentialReady(true)
                .judgeVersion("judge-runtime-v2").calibrationVersion("cal-v1").build();
        assertEquals(EvalControlPlaneErrorCode.PROFILE_CASE_CONFLICT,
                assertThrows(EvalControlPlaneException.class,
                        () -> resolver.verifyLiveRuntime(run, changed)).getCode());
    }

    private EvaluationProfileResolver resolver(List<EvaluationProfileVersion> profiles) {
        return new EvaluationProfileResolver(() -> profiles);
    }
}
