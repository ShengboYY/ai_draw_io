package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.EvalCaseLoader;
import org.zipp.ai.domain.agent.service.evaluation.intake.EvalDraftSanitizer;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies deterministic schema, replay and privacy checks before any dry run. */
@Service
public class EvalCaseValidationService {
    private static final String VERSION = "case-validation-v1";
    private static final List<String> FORBIDDEN_KEYS = List.of(
            "sourceRunId", "sourceSpanId", "debugCaptureId", "candidateId", "Authorization", "Cookie");
    private static final Pattern CELL_VALUE = Pattern.compile("(?i)\\bvalue\\s*=\\s*[\"']([^\"']{1,512})[\"']");

    private final EvalCaseWorkingCopyService workingCopies;
    private final IEvalCaseEvidenceStore evidenceStore;
    private final Clock clock;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final EvalCaseLoader loader = new EvalCaseLoader();

    @Autowired
    public EvalCaseValidationService(EvalCaseWorkingCopyService workingCopies,
                                     IEvalCaseEvidenceStore evidenceStore) {
        this(workingCopies, evidenceStore, Clock.systemUTC());
    }

    public EvalCaseValidationService(EvalCaseWorkingCopyService workingCopies,
                                     IEvalCaseEvidenceStore evidenceStore, Clock clock) {
        this.workingCopies = workingCopies;
        this.evidenceStore = evidenceStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EvalCaseValidationResult validate(String id, String actor, EvalAdminRole role) {
        EvalCaseWorkingCopy validating = workingCopies.transition(id, EvalCaseWorkingCopyStatus.VALIDATING, actor, role);
        List<String> failures = new ArrayList<>();
        validateCanonicalSchema(validating.getDefinition(), failures);
        validateReplay(validating.getDefinition(), failures);
        validatePrivacy(validating.getDefinition(), failures);
        boolean passed = failures.isEmpty();
        EvalCaseWorkingCopy completed = workingCopies.transition(id,
                passed ? EvalCaseWorkingCopyStatus.VALIDATED : EvalCaseWorkingCopyStatus.VALIDATION_FAILED,
                actor, role);
        evidenceStore.insert(EvalCaseEvidence.builder().id("ece_" + UUID.randomUUID())
                .workingCopyId(id).workingCopyRevision(completed.getRevision()).type(EvalCaseEvidenceType.VALIDATION)
                .status(passed ? "PASS" : "FAIL").payloadJson(JSON.toJSONString(failures))
                .componentVersion(VERSION).createdAt(clock.instant()).build());
        return EvalCaseValidationResult.builder().passed(passed).evidence(failures).workingCopy(completed).build();
    }

    private void validateCanonicalSchema(EvalCaseDefinition definition, List<String> failures) {
        try {
            byte[] yaml = yamlMapper.writeValueAsBytes(definition);
            loader.load(new ByteArrayInputStream(yaml));
        } catch (Exception e) {
            failures.add("canonical schema: " + e.getMessage());
        }
    }

    private void validateReplay(EvalCaseDefinition definition, List<String> failures) {
        EvalCaseDefinition.Replay replay = definition == null ? null : definition.getReplay();
        if (replay == null) {
            failures.add("replay is required for deterministic dry run");
            return;
        }
        if (blank(replay.getInitialCanvasXml())) failures.add("replay.initialCanvasXml is required");
        if (replay.getTurns() == null || replay.getTurns().isEmpty()) {
            if (blank(replay.getRouterReply())) failures.add("replay.routerReply is required");
            return;
        }
        Object inputTurns = definition.getInput() == null ? null : definition.getInput().get("turns");
        if (!(inputTurns instanceof List<?> turns) || turns.size() != replay.getTurns().size()) {
            failures.add("input.turns and replay.turns must have the same size");
        }
        if (replay.getTurns().stream().anyMatch(turn -> blank(turn.getRouterReply()))) {
            failures.add("every replay turn requires routerReply");
        }
    }

    private void validatePrivacy(EvalCaseDefinition definition, List<String> failures) {
        String serialized = JSON.toJSONString(definition);
        for (String key : FORBIDDEN_KEYS) {
            if (serialized.contains("\"" + key + "\"")) failures.add("forbidden provenance or credential field: " + key);
        }
        if (definition != null && definition.getExecutionProfile() != null
                && !blank(definition.getExecutionProfile().getModelCredentialId())) {
            failures.add("published case must not contain a model credential identifier");
        }
        EvalDraftSanitizer.Result privacy = new EvalDraftSanitizer().sanitize(reviewableContents(definition));
        if (!privacy.safeForModel() || !privacy.removedCategories().isEmpty()) {
            failures.add("privacy sanitizer rejected case text: " + String.join(",", privacy.removedCategories()));
        }
    }

    private List<String> reviewableContents(EvalCaseDefinition definition) {
        if (definition == null) return List.of();
        List<String> values = new ArrayList<>();
        // Versioned identifiers are metadata, not user-visible content, and can resemble API-key prefixes.
        values.add(String.join("\n", List.of(text(definition.getOrigin()), text(definition.getDiagramType()))));
        values.add(JSON.toJSONString(definition.getTags()));
        values.add(JSON.toJSONString(definition.getInput()));
        values.add(JSON.toJSONString(definition.getExpected()));
        values.add(JSON.toJSONString(definition.getExecutionProfile()));
        values.add(JSON.toJSONString(definition.getRegression()));
        EvalCaseDefinition.Replay replay = definition.getReplay();
        if (replay != null) {
            values.add(text(replay.getRouterReply()));
            addCellLabels(values, replay.getInitialCanvasXml());
            if (replay.getToolCalls() != null) replay.getToolCalls().forEach(call -> addToolCall(values, call));
            if (replay.getTurns() != null) replay.getTurns().forEach(turn -> {
                values.add(text(turn.getRouterReply()));
                if (turn.getToolCalls() != null) turn.getToolCalls().forEach(call -> addToolCall(values, call));
            });
        }
        return values.stream().filter(value -> !blank(value)).toList();
    }

    private void addToolCall(List<String> values, EvalCaseDefinition.ReplayToolCall call) {
        if (call == null) return;
        values.add(String.join("\n", List.of(text(call.getName()), text(call.getMode()),
                text(call.getExpectedRepairContains()))));
        addCellLabels(values, call.getXml());
        addCellLabels(values, call.getCells());
    }

    private void addCellLabels(List<String> values, String xml) {
        if (blank(xml)) return;
        Matcher matcher = CELL_VALUE.matcher(xml);
        while (matcher.find()) values.add(matcher.group(1));
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
