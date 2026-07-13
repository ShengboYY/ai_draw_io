package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.EvalCaseLoader;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Applies deterministic schema, replay and privacy checks before any dry run. */
@Service
public class EvalCaseValidationService {
    private static final String VERSION = "case-validation-v1";
    private static final List<String> FORBIDDEN_KEYS = List.of(
            "sourceRunId", "sourceSpanId", "debugCaptureId", "candidateId", "Authorization", "Cookie");

    private final EvalCaseWorkingCopyService workingCopies;
    private final IEvalCaseEvidenceStore evidenceStore;
    private final Clock clock;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final EvalCaseLoader loader = new EvalCaseLoader();

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
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
