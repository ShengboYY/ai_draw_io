package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalAdminRole;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseSourceType;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopy;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopyStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvaluationTargetInference;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseDraft;
import org.zipp.ai.domain.agent.service.evaluation.EvalCaseLoader;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;

import java.time.Clock;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns Working Copy identity, ownership and optimistic concurrency rules. */
@Service
public class EvalCaseWorkingCopyService {
    private final IEvalCaseWorkingCopyStore store;
    private final ITraceToEvalStore traceStore;
    private final Clock clock;
    private final EvalCaseLoader caseLoader;
    private final EvaluationTargetInferenceService targetInference;

    @Autowired
    public EvalCaseWorkingCopyService(IEvalCaseWorkingCopyStore store, ITraceToEvalStore traceStore) {
        this(store, traceStore, Clock.systemUTC(), new EvalCaseLoader());
    }

    public EvalCaseWorkingCopyService(IEvalCaseWorkingCopyStore store, ITraceToEvalStore traceStore, Clock clock) {
        this(store, traceStore, clock, new EvalCaseLoader());
    }

    EvalCaseWorkingCopyService(IEvalCaseWorkingCopyStore store, ITraceToEvalStore traceStore,
                               Clock clock, EvalCaseLoader caseLoader) {
        this.store = store;
        this.traceStore = traceStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.caseLoader = caseLoader;
        this.targetInference = new EvaluationTargetInferenceService();
    }

    public EvalCaseWorkingCopy createManual(EvalCaseDefinition definition, String actor, EvalAdminRole role) {
        return create(EvalCaseSourceType.MANUAL, definition, null, actor, role);
    }

    public EvalCaseWorkingCopy createImported(EvalCaseDefinition definition, String actor, EvalAdminRole role) {
        return create(EvalCaseSourceType.IMPORTED, definition, null, actor, role);
    }

    public EvalCaseWorkingCopy createPublishedClone(EvalCaseDefinition definition, String actor, EvalAdminRole role) {
        return create(EvalCaseSourceType.PUBLISHED_CASE_CLONE, definition, null, actor, role);
    }

    public EvalCaseWorkingCopy createImportedYaml(String yaml, String actor, EvalAdminRole role) {
        require(yaml, "yaml");
        try (ByteArrayInputStream input = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
            return createImported(caseLoader.load(input), actor, role);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid Eval Case YAML", e);
        }
    }

    public EvalCaseWorkingCopy createFromTraceDraft(String candidateId, String caseId, String caseVersion,
                                                     String actor, EvalAdminRole role) {
        requireEditor(role);
        require(candidateId, "candidateId");
        EvalCaseDraft draft = traceStore.findLatestDraft(candidateId)
                .orElseThrow(() -> new EvalControlPlaneException(
                        EvalControlPlaneErrorCode.NOT_FOUND, "Eval Draft not found"));
        EvalCaseDefinition definition = fromDraft(draft, caseId, caseVersion);
        return create(EvalCaseSourceType.TRACE_DRAFT, definition, candidateId, actor, role);
    }

    public EvalCaseWorkingCopy get(String id, String actor, EvalAdminRole role) {
        EvalCaseWorkingCopy workingCopy = find(id);
        requireCanRead(workingCopy, actor, role);
        return workingCopy;
    }

    public List<EvalCaseWorkingCopy> list(String status, String ownerUserId, int requestedLimit, int requestedOffset,
                                          String actor, EvalAdminRole role) {
        requireActor(actor);
        requireEditor(role);
        EvalCaseWorkingCopyStatus parsedStatus = parseStatus(status);
        String effectiveOwner = canManageAll(role) ? StringUtils.trimToNull(ownerUserId) : actor;
        return store.list(parsedStatus, effectiveOwner, Math.max(1, Math.min(requestedLimit, 200)),
                Math.max(0, requestedOffset));
    }

    public EvalCaseWorkingCopy update(String id, long expectedRevision, EvalCaseDefinition definition,
                                      String actor, EvalAdminRole role) {
        EvalCaseWorkingCopy current = find(id);
        requireCanEdit(current, actor, role);
        if (!editable(current.getStatus())) {
            throw new IllegalStateException("working copy is not editable in status=" + current.getStatus());
        }
        requireDefinition(definition);
        if (!current.getCaseId().equals(definition.getCaseId())
                || !current.getCaseVersion().equals(definition.getCaseVersion())) {
            throw new IllegalArgumentException("case identity cannot change during update");
        }
        EvaluationTargetInference inferred = normalizeTarget(definition);
        EvalCaseWorkingCopy updated = EvalCaseWorkingCopy.builder()
                .id(current.getId()).caseId(current.getCaseId()).caseVersion(current.getCaseVersion())
                .sourceType(current.getSourceType()).candidateId(current.getCandidateId())
                .status(EvalCaseWorkingCopyStatus.DRAFT).ownerUserId(current.getOwnerUserId())
                .revision(expectedRevision + 1).definition(copy(definition))
                .evaluationTarget(inferred.target()).targetMigrationStatus(inferred.status())
                .createdAt(current.getCreatedAt()).updatedAt(clock.instant()).build();
        if (!store.update(updated, expectedRevision)) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.REVISION_CONFLICT,
                    "working copy revision conflict");
        }
        return updated;
    }

    public EvalCaseWorkingCopy cloneWorkingCopy(String sourceId, String newCaseId, String newCaseVersion,
                                                String actor, EvalAdminRole role) {
        EvalCaseWorkingCopy source = get(sourceId, actor, role);
        EvalCaseDefinition definition = copy(source.getDefinition());
        definition.setCaseId(require(newCaseId, "caseId"));
        definition.setCaseVersion(require(newCaseVersion, "caseVersion"));
        return create(EvalCaseSourceType.WORKING_COPY_CLONE, definition, null, actor, role);
    }

    /** Advances the qualification state machine without mutating the case definition. */
    public EvalCaseWorkingCopy transition(String id, EvalCaseWorkingCopyStatus target,
                                          String actor, EvalAdminRole role) {
        EvalCaseWorkingCopy current = find(id);
        if (target == EvalCaseWorkingCopyStatus.APPROVED || target == EvalCaseWorkingCopyStatus.REJECTED) {
            requireCanRead(current, actor, role);
        } else {
            requireCanEdit(current, actor, role);
        }
        if (!allowedTransition(current.getStatus(), target)) {
            throw new IllegalStateException("invalid working-copy transition " + current.getStatus() + " -> " + target);
        }
        EvalCaseWorkingCopy updated = EvalCaseWorkingCopy.builder()
                .id(current.getId()).caseId(current.getCaseId()).caseVersion(current.getCaseVersion())
                .sourceType(current.getSourceType())
                // Publication severs the short-lived Candidate-to-production trace backlink.
                .candidateId(target == EvalCaseWorkingCopyStatus.PUBLISHED ? null : current.getCandidateId())
                .status(target)
                .ownerUserId(current.getOwnerUserId()).revision(current.getRevision() + 1)
                .definition(copy(current.getDefinition())).evaluationTarget(current.getEvaluationTarget())
                .targetMigrationStatus(current.getTargetMigrationStatus()).createdAt(current.getCreatedAt())
                .updatedAt(clock.instant()).build();
        if (!store.update(updated, current.getRevision())) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.REVISION_CONFLICT,
                    "working copy revision conflict");
        }
        return updated;
    }

    private EvalCaseWorkingCopy create(EvalCaseSourceType sourceType, EvalCaseDefinition definition,
                                       String candidateId, String actor, EvalAdminRole role) {
        requireActor(actor);
        requireEditor(role);
        requireDefinition(definition);
        EvaluationTargetInference inferred = normalizeTarget(definition);
        EvalCaseWorkingCopy workingCopy = EvalCaseWorkingCopy.builder()
                .id("ecw_" + UUID.randomUUID())
                .caseId(definition.getCaseId()).caseVersion(definition.getCaseVersion())
                .sourceType(sourceType).candidateId(StringUtils.trimToNull(candidateId))
                .status(EvalCaseWorkingCopyStatus.DRAFT).ownerUserId(actor).revision(1L)
                .definition(copy(definition)).evaluationTarget(inferred.target())
                .targetMigrationStatus(inferred.status()).createdAt(clock.instant()).updatedAt(clock.instant()).build();
        store.insert(workingCopy);
        return workingCopy;
    }

    private EvalCaseDefinition fromDraft(EvalCaseDraft draft, String caseId, String caseVersion) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("turns", draft.getUserTurns() == null ? List.of() : List.copyOf(draft.getUserTurns()));
        Map<String, Object> draftReview = new LinkedHashMap<>();
        draftReview.put("failureSummary", StringUtils.trimToNull(draft.getFailureSummary()));
        draftReview.put("initialFixtureHint", StringUtils.trimToNull(draft.getInitialFixtureHint()));
        draftReview.put("suggestedAssertions", draft.getSuggestedAssertions() == null
                ? List.of() : List.copyOf(draft.getSuggestedAssertions()));
        draftReview.put("confidence", StringUtils.trimToNull(draft.getConfidence()));
        draftReview.put("modelVersion", StringUtils.trimToNull(draft.getModelVersion()));
        // Keep sanitized model suggestions visible in Case Studio without treating them as executable assertions.
        input.put("draftReview", draftReview);
        List<String> tags = new ArrayList<>();
        tags.add("trace-derived");
        if (StringUtils.isNotBlank(draft.getSuspectedFailureFamily())) {
            tags.add("failure:" + draft.getSuspectedFailureFamily());
        }
        EvalCaseDefinition.Expected expected = new EvalCaseDefinition.Expected();
        expected.setRouteType(StringUtils.trimToNull(draft.getExpectedRoute()));
        EvaluationTarget target = targetFromDraft(draft);
        if (target == null) tags.add("target:ambiguous");
        return EvalCaseDefinition.builder()
                .caseId(require(caseId, "caseId")).caseVersion(require(caseVersion, "caseVersion"))
                .datasetVersion("dev-draft").origin("trace-derived-synthetic").risk("high")
                .evaluationTarget(target)
                .tags(tags).input(input)
                .privacy(new EvalCaseDefinition.Privacy("synthetic",
                        StringUtils.defaultIfBlank(draft.getSanitizerVersion(), "unknown")))
                .expected(expected)
                .provenance(EvalCaseDefinition.Provenance.builder().sourceTraceRetained(false).build())
                .build();
    }

    private EvaluationTarget targetFromDraft(EvalCaseDraft draft) {
        String family = StringUtils.lowerCase(StringUtils.defaultString(draft.getSuspectedFailureFamily()));
        if (family.contains("intent") || family.contains("route")) return EvaluationTarget.INTENT_ROUTER;
        if (family.contains("visual") || family.contains("drawing") || family.contains("layout")
                || family.contains("overlap") || family.contains("canvas_quality")) {
            return EvaluationTarget.DRAWING_QUALITY;
        }
        if (family.contains("tool") || family.contains("latency") || family.contains("completion")
                || family.contains("load") || family.contains("agent")) return EvaluationTarget.FULL_AGENT;
        return null;
    }

    private EvaluationTargetInference normalizeTarget(EvalCaseDefinition definition) {
        EvaluationTargetInference inferred = targetInference.infer(definition);
        if (inferred.target() != null) definition.setEvaluationTarget(inferred.target());
        return inferred;
    }

    private EvalCaseWorkingCopy find(String id) {
        require(id, "workingCopyId");
        return store.find(id).orElseThrow(() -> new EvalControlPlaneException(
                EvalControlPlaneErrorCode.NOT_FOUND, "working copy not found"));
    }

    private void requireDefinition(EvalCaseDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("definition is required");
        require(definition.getCaseId(), "caseId");
        require(definition.getCaseVersion(), "caseVersion");
    }

    private void requireCanRead(EvalCaseWorkingCopy workingCopy, String actor, EvalAdminRole role) {
        requireActor(actor);
        requireEditor(role);
        if (!canManageAll(role) && !actor.equals(workingCopy.getOwnerUserId())) {
            throw new SecurityException("working copy is owned by another administrator");
        }
    }

    private void requireCanEdit(EvalCaseWorkingCopy workingCopy, String actor, EvalAdminRole role) {
        requireCanRead(workingCopy, actor, role);
        if (role == EvalAdminRole.REVIEWER && !actor.equals(workingCopy.getOwnerUserId())) {
            throw new SecurityException("reviewers cannot edit another administrator's working copy");
        }
    }

    private boolean canManageAll(EvalAdminRole role) {
        return role == EvalAdminRole.REVIEWER || role == EvalAdminRole.ADMIN || role == EvalAdminRole.RELEASE_OWNER;
    }

    private boolean editable(EvalCaseWorkingCopyStatus status) {
        return status == EvalCaseWorkingCopyStatus.DRAFT
                || status == EvalCaseWorkingCopyStatus.VALIDATION_FAILED
                || status == EvalCaseWorkingCopyStatus.REJECTED;
    }

    private boolean allowedTransition(EvalCaseWorkingCopyStatus source, EvalCaseWorkingCopyStatus target) {
        return switch (source) {
            case DRAFT, VALIDATION_FAILED -> target == EvalCaseWorkingCopyStatus.VALIDATING;
            case VALIDATING -> target == EvalCaseWorkingCopyStatus.VALIDATED
                    || target == EvalCaseWorkingCopyStatus.VALIDATION_FAILED;
            case VALIDATED -> target == EvalCaseWorkingCopyStatus.DRY_RUNNING;
            case DRY_RUNNING -> target == EvalCaseWorkingCopyStatus.DRY_RUN_PASSED
                    || target == EvalCaseWorkingCopyStatus.DRY_RUN_FAILED;
            case DRY_RUN_PASSED -> target == EvalCaseWorkingCopyStatus.UNDER_REVIEW;
            case UNDER_REVIEW -> target == EvalCaseWorkingCopyStatus.APPROVED
                    || target == EvalCaseWorkingCopyStatus.REJECTED;
            case APPROVED -> target == EvalCaseWorkingCopyStatus.PUBLISHED;
            default -> false;
        };
    }

    private EvalCaseWorkingCopyStatus parseStatus(String status) {
        if (StringUtils.isBlank(status)) return null;
        try {
            return EvalCaseWorkingCopyStatus.valueOf(StringUtils.upperCase(StringUtils.trim(status)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported working copy status");
        }
    }

    private void requireActor(String actor) {
        require(actor, "actor");
    }

    private void requireEditor(EvalAdminRole role) {
        if (role == null) throw new SecurityException("Evaluation role is required");
    }

    private String require(String value, String field) {
        if (StringUtils.isBlank(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    /** EvalCaseDefinition is mutable, so clones and store writes receive an isolated graph. */
    private EvalCaseDefinition copy(EvalCaseDefinition definition) {
        return JSON.parseObject(JSON.toJSONString(definition), EvalCaseDefinition.class);
    }
}
