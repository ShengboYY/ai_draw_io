package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Resolves presets into safe canonical Run snapshots; it is the only allowed snapshot constructor. */
@Service
public class EvaluationProfileResolver {
    private static final Set<String> CREDENTIAL_REFERENCE_KEYS = Set.of("credentialalias", "credentialversion");
    private final IEvaluationProfileCatalog catalog;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public EvaluationProfileResolver(IEvaluationProfileCatalog catalog) { this.catalog = catalog; }

    public List<EvaluationProfileVersion> list() { return catalog.list(); }

    /** Resolves an explicit Profile, or derives one immutable compatibility snapshot for legacy Cases. */
    public EvaluationProfileSnapshot resolveForRun(String profileId, String profileVersion,
                                                    EvaluationTarget target, EvalRunMode requestedMode,
                                                    List<EvalCaseDefinition> definitions) {
        boolean hasLegacy = definitions != null && definitions.stream()
                .anyMatch(definition -> definition.getExecutionProfile() != null);
        if (blank(profileId) && hasLegacy) return legacySnapshot(target, requestedMode, definitions);
        EvaluationProfileSnapshot snapshot = resolve(profileId, profileVersion, target, requestedMode);
        verifyLegacyCompatibility(snapshot, definitions == null ? List.of() : definitions);
        return snapshot;
    }

    public EvaluationProfileSnapshot resolve(String profileId, String profileVersion,
                                             EvaluationTarget target, EvalRunMode requestedMode) {
        EvaluationProfileVersion profile = blank(profileId)
                ? defaultProfile(target, requestedMode)
                : catalog.find(profileId, blank(profileVersion) ? "1" : profileVersion)
                .orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.NOT_FOUND,
                        "Evaluation Profile version not found"));
        if (profile.target() != target) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.TARGET_MISMATCH,
                    "Evaluation Profile target does not match Dataset target");
        }
        if (requestedMode != null && !modeCompatible(profile, requestedMode)) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.PROFILE_CASE_CONFLICT,
                    "Evaluation Profile mode does not match requested Run mode");
        }
        String canonical = canonicalSnapshot(profile);
        return new EvaluationProfileSnapshot(profile.profileId(), profile.version(), profile.target(),
                profile.runnerAdapter(), profile.mode(), profile.repetitions(), profile.gateEligible(),
                canonical, sha256(canonical), false);
    }

    public void verifyLegacyCompatibility(EvaluationProfileSnapshot snapshot, List<EvalCaseDefinition> definitions) {
        Map<String, Object> config = config(snapshot.canonicalConfigJson());
        for (EvalCaseDefinition definition : definitions) {
            EvalCaseDefinition.ExecutionProfile legacy = definition.getExecutionProfile();
            if (legacy == null) continue;
            requireMatch(definition, "model", legacy.getModel(), config.get("model"));
            requireMatch(definition, "temperature", legacy.getTemperature(), config.get("temperature"));
            requireMatch(definition, "promptConfigHash", legacy.getPromptConfigHash(), config.get("promptConfigHash"));
            requireMatch(definition, "skillCatalogHash", legacy.getSkillCatalogHash(), config.get("skillCatalogHash"));
            requireMatch(definition, "toolPolicyVersion", legacy.getToolPolicyVersion(), config.get("toolPolicyVersion"));
            requireMatch(definition, "modelCredentialId", legacy.getModelCredentialId(), config.get("credentialAlias"));
            requireMatch(definition, "maxReviewIterations", legacy.getMaxReviewIterations(), config.get("maxReviewIterations"));
            requireMatch(definition, "inputPricePerMillion", legacy.getInputPricePerMillion(), config.get("inputPricePerMillion"));
            requireMatch(definition, "outputPricePerMillion", legacy.getOutputPricePerMillion(), config.get("outputPricePerMillion"));
        }
    }

    /** Captures the exact non-sensitive live Judge/calibration wiring resolved for this Run. */
    public EvaluationProfileSnapshot bindLiveRuntime(EvaluationProfileSnapshot snapshot, EvalLiveRunReadiness readiness) {
        if (snapshot.mode() == EvalRunMode.MODE_B || readiness == null) return snapshot;
        Map<String, Object> root = root(snapshot.canonicalConfigJson());
        Map<String, Object> runtime = new TreeMap<>();
        put(runtime, "judgeVersion", readiness.getJudgeVersion());
        put(runtime, "calibrationVersion", readiness.getCalibrationVersion());
        put(runtime, "visualJudgeVersion", readiness.getVisualJudgeVersion());
        put(runtime, "visualCalibrationVersion", readiness.getVisualCalibrationVersion());
        runtime.put("providerCredentialReady", readiness.isProviderCredentialReady());
        root.put("runtime", runtime);
        String canonical = json(sort(root));
        return new EvaluationProfileSnapshot(snapshot.profileId(), snapshot.profileVersion(), snapshot.target(),
                snapshot.runnerAdapter(), snapshot.mode(), snapshot.repetitions(), snapshot.gateEligible(),
                canonical, sha256(canonical), snapshot.customized());
    }

    /** Fails closed when a restarted worker no longer matches the runtime versions frozen in the Run. */
    public void verifyLiveRuntime(EvalRun run, EvalLiveRunReadiness readiness) {
        if (run.getMode() == EvalRunMode.MODE_B || readiness == null) return;
        Map<String, Object> runtime = map(root(run.getProfileSnapshotJson()).get("runtime"));
        requireRuntime(runtime, "judgeVersion", readiness.getJudgeVersion());
        requireRuntime(runtime, "calibrationVersion", readiness.getCalibrationVersion());
        requireRuntime(runtime, "visualJudgeVersion", readiness.getVisualJudgeVersion());
        requireRuntime(runtime, "visualCalibrationVersion", readiness.getVisualCalibrationVersion());
    }

    /** Projects the frozen Run snapshot into legacy adapter fields until R4 removes that seam. */
    public void materializeExecutionConfig(EvalRun run, List<EvalCaseDefinition> definitions) {
        Map<String, Object> config = config(run.getProfileSnapshotJson());
        if (config.isEmpty()) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.PROFILE_CASE_CONFLICT,
                    "Partial legacy Run snapshot cannot be replayed as a reproducible Evaluation");
        }
        EvalCaseDefinition.ExecutionProfile resolved = EvalCaseDefinition.ExecutionProfile.builder()
                .profileId(run.getProfileId() + "@" + run.getProfileVersion())
                .model(text(config.get("model"))).temperature(decimal(config.get("temperature")))
                .promptConfigHash(text(config.get("promptConfigHash")))
                .skillCatalogHash(text(config.get("skillCatalogHash")))
                .toolPolicyVersion(text(config.get("toolPolicyVersion")))
                .modelCredentialId("server-default".equals(text(config.get("credentialAlias")))
                        ? null : text(config.get("credentialAlias")))
                .maxReviewIterations(integerOrNull(config.get("maxReviewIterations")))
                .inputPricePerMillion(decimal(config.get("inputPricePerMillion")))
                .outputPricePerMillion(decimal(config.get("outputPricePerMillion"))).build();
        definitions.forEach(definition -> definition.setExecutionProfile(resolved));
    }

    /** Ensures Episode artifacts identify the exact Run snapshot, not a Case-local approximation. */
    public void applyExecutionMetadata(EvalRun run, EvalExecution execution) {
        if (execution == null) return;
        Map<String, Object> config = config(run.getProfileSnapshotJson());
        execution.setExecutionProfileHash(run.getProfileConfigHash());
        execution.setPromptConfigHash(text(config.get("promptConfigHash")));
        execution.setSkillCatalogHash(text(config.get("skillCatalogHash")));
        execution.setToolPolicyVersion(text(config.get("toolPolicyVersion")));
    }

    /** Returns the immutable execution policy embedded in the persisted Profile snapshot. */
    public EvaluationProfilePolicy policy(EvaluationProfileSnapshot snapshot) {
        Map<String, Object> config = config(snapshot.canonicalConfigJson());
        Map<String, Object> gate = map(config.get("gatePolicy"));
        return new EvaluationProfilePolicy(number(config.get("maxEstimatedCost"), 10D),
                integer(gate.get("minimumCases"), 1), number(gate.get("maximumErrorRate"), 0.05D),
                integer(gate.get("minimumPairedCases"), 1), number(gate.get("regressionThreshold"), 0D));
    }

    /** Keeps the executed grader manifest aligned with the versioned Profile definition. */
    public String graderManifestJson(EvaluationProfileSnapshot snapshot) {
        Object graders = config(snapshot.canonicalConfigJson()).get("graders");
        List<String> manifest = graders instanceof Collection<?> values
                ? values.stream().map(String::valueOf).toList()
                : Arrays.stream(String.valueOf(graders == null ? "" : graders).split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        try { return mapper.writeValueAsString(manifest); }
        catch (JsonProcessingException e) { throw new IllegalStateException("grader manifest cannot be serialized", e); }
    }

    private EvaluationProfileVersion defaultProfile(EvaluationTarget target, EvalRunMode mode) {
        EvalRunMode effectiveMode = mode == null ? EvalRunMode.MODE_B : mode;
        return catalog.list().stream().filter(profile -> profile.target() == target && modeCompatible(profile, effectiveMode))
                .findFirst().orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.NOT_FOUND,
                        "No default Evaluation Profile matches this Target and mode"));
    }

    private EvaluationProfileSnapshot legacySnapshot(EvaluationTarget target, EvalRunMode requestedMode,
                                                     List<EvalCaseDefinition> definitions) {
        if (requestedMode == EvalRunMode.RELEASE) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.PROFILE_CASE_CONFLICT,
                    "Release Runs require an explicit gate-eligible Evaluation Profile");
        }
        List<Map<String, Object>> legacyConfigs = definitions.stream()
                .map(EvalCaseDefinition::getExecutionProfile).filter(Objects::nonNull)
                .map(this::legacyConfig).distinct().toList();
        if (legacyConfigs.size() != 1) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.PROFILE_CASE_CONFLICT,
                    "Legacy Cases in one Dataset must share one execution config");
        }
        EvaluationProfileVersion base = defaultProfile(target, requestedMode);
        Map<String, Object> merged = new TreeMap<>(mapJson(base.configJson()));
        merged.putAll(legacyConfigs.get(0));
        String configJson = json(sort(merged));
        String version = sha256(configJson).substring(0, 12);
        EvaluationProfileVersion generated = new EvaluationProfileVersion("legacy-case", version, target,
                base.runnerAdapter(), base.mode(), base.repetitions(), false, configJson);
        String canonical = canonicalSnapshot(generated);
        return new EvaluationProfileSnapshot(generated.profileId(), generated.version(), target,
                generated.runnerAdapter(), generated.mode(), generated.repetitions(), false,
                canonical, sha256(canonical), true);
    }

    private Map<String, Object> legacyConfig(EvalCaseDefinition.ExecutionProfile legacy) {
        Map<String, Object> values = new TreeMap<>();
        put(values, "model", legacy.getModel()); put(values, "temperature", legacy.getTemperature());
        put(values, "promptConfigHash", legacy.getPromptConfigHash());
        put(values, "skillCatalogHash", legacy.getSkillCatalogHash());
        put(values, "toolPolicyVersion", legacy.getToolPolicyVersion());
        put(values, "credentialAlias", legacy.getModelCredentialId());
        put(values, "maxReviewIterations", legacy.getMaxReviewIterations());
        put(values, "inputPricePerMillion", legacy.getInputPricePerMillion());
        put(values, "outputPricePerMillion", legacy.getOutputPricePerMillion());
        return values;
    }

    private boolean modeCompatible(EvaluationProfileVersion profile, EvalRunMode requestedMode) {
        // RELEASE is live execution plus comparison/gating; its component Profile remains MODE_C.
        return profile.mode() == requestedMode
                || requestedMode == EvalRunMode.RELEASE && profile.mode() == EvalRunMode.MODE_C && profile.gateEligible();
    }

    @SuppressWarnings("unchecked")
    private String canonicalSnapshot(EvaluationProfileVersion profile) {
        try {
            Object raw = mapper.readValue(profile.configJson(), Object.class);
            rejectSecrets(raw);
            Map<String, Object> root = new TreeMap<>();
            root.put("config", sort(raw));
            root.put("gateEligible", profile.gateEligible());
            root.put("mode", profile.mode().name());
            root.put("profileId", profile.profileId());
            root.put("profileVersion", profile.version());
            root.put("repetitions", profile.repetitions());
            root.put("runnerAdapter", profile.runnerAdapter());
            root.put("target", profile.target().name());
            return json(root);
        } catch (JsonProcessingException e) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.VALIDATION_FAILED,
                    "Evaluation Profile config is not valid JSON");
        }
    }

    @SuppressWarnings("unchecked")
    private void rejectSecrets(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                String compactKey = key.replace("_", "").replace("-", "");
                boolean secret = compactKey.contains("apikey") || compactKey.contains("privatekey")
                        || compactKey.endsWith("token") || compactKey.endsWith("secret")
                        || compactKey.endsWith("password")
                        || compactKey.startsWith("credential") && !CREDENTIAL_REFERENCE_KEYS.contains(compactKey);
                if (secret) throw new EvalControlPlaneException(EvalControlPlaneErrorCode.VALIDATION_FAILED,
                        "Evaluation Profile config must reference credential alias/version, never secrets");
                rejectSecrets(entry.getValue());
            }
        } else if (value instanceof Collection<?> values) {
            values.forEach(this::rejectSecrets);
        }
    }

    private Object sort(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), sort(item)));
            return sorted;
        }
        if (value instanceof Collection<?> values) return values.stream().map(this::sort).toList();
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(String snapshotJson) {
        return map(root(snapshotJson).get("config"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> root(String snapshotJson) {
        try { return new TreeMap<>((Map<String, Object>) mapper.readValue(snapshotJson, Map.class)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("stored Profile snapshot is invalid", e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapJson(String value) {
        try { return (Map<String, Object>) mapper.readValue(value, Map.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Profile config is invalid", e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private int integer(Object value, int fallback) {
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : fallback;
    }

    private Integer integerOrNull(Object value) { return value instanceof Number number ? number.intValue() : null; }
    private Double decimal(Object value) { return value instanceof Number number ? number.doubleValue() : null; }
    private String text(Object value) { return value == null ? null : String.valueOf(value); }

    private void requireRuntime(Map<String, Object> frozen, String field, String current) {
        String expected = text(frozen.get(field));
        if (!blank(expected) && !Objects.equals(expected, current)) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.PROFILE_CASE_CONFLICT,
                    "Live runtime " + field + " changed after the Run snapshot was created");
        }
    }

    private void put(Map<String, Object> values, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) values.put(key, value);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Profile snapshot cannot be serialized", e); }
    }

    private void requireMatch(EvalCaseDefinition definition, String field, Object legacy, Object resolved) {
        if (legacy == null) return;
        boolean equal = legacy instanceof Number left && resolved instanceof Number right
                ? Double.compare(left.doubleValue(), right.doubleValue()) == 0
                : Objects.equals(String.valueOf(legacy), resolved == null ? null : String.valueOf(resolved));
        if (!equal) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.PROFILE_CASE_CONFLICT,
                    "Case " + definition.getCaseId() + " legacy " + field + " conflicts with Evaluation Profile");
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record EvaluationProfilePolicy(double maxEstimatedCost, int minimumCases,
                                          double maximumErrorRate, int minimumPairedCases,
                                          double regressionThreshold) { }
}
