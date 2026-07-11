package org.zipp.ai.domain.agent.service.evaluation.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseDraft;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Controlled Trace-to-Eval draft pipeline. Human review remains mandatory after DRAFT_READY. */
@Service
public class TraceToEvalDraftService {
    private static final Set<String> FAMILIES = Set.of("routing", "tool_policy", "artifact", "layout", "semantic", "response", "execution");
    private static final Set<String> CONFIDENCE = Set.of("low", "medium", "high");
    private static final Pattern PRODUCTION_ID = Pattern.compile("(?i)\\b(?:run|aru|usr|ecc|adt)_[A-Za-z0-9_-]+\\b");
    private final ITraceToEvalStore store;
    private final AgentDebugTraceService debugTraceService;
    private final IEvalDraftModel model;
    private final EvalDraftSanitizer sanitizer;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public TraceToEvalDraftService(ITraceToEvalStore store, AgentDebugTraceService debugTraceService, IEvalDraftModel model) {
        this(store, debugTraceService, model, new EvalDraftSanitizer(), Clock.systemUTC());
    }

    TraceToEvalDraftService(ITraceToEvalStore store, AgentDebugTraceService debugTraceService,
                            IEvalDraftModel model, EvalDraftSanitizer sanitizer, Clock clock) {
        this.store = store; this.debugTraceService = debugTraceService; this.model = model;
        this.sanitizer = sanitizer; this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Preparation prepare(String candidateId, String actor, boolean purposeConfirmed, String ipAddress, String userAgent) {
        if (!purposeConfirmed) throw new IllegalArgumentException("evaluation draft purpose confirmation is required");
        EvalCaseCandidate candidate = store.findCandidate(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("candidate not found"));
        if (candidate.getStatus() != EvalCandidateStatus.TRIAGED) {
            throw new IllegalStateException("candidate must be TRIAGED before draft preparation");
        }
        List<DebugTraceCapture> captures = debugTraceService.viewCapturesForRun(
                actor, candidate.getSourceRunId(), ipAddress, userAgent);
        List<String> content = new java.util.ArrayList<>();
        // Candidate summaries are operator-authored metadata and must cross the same sanitizer boundary as captures.
        if (StringUtils.isNotBlank(candidate.getEvidenceSummary())) content.add(candidate.getEvidenceSummary());
        content.addAll(captures.stream().map(DebugTraceCapture::getContent).filter(StringUtils::isNotBlank).toList());
        EvalDraftSanitizer.Result sanitized = sanitizer.sanitize(content);
        if (!sanitized.safeForModel()) return manual(candidate, String.join(",", sanitized.removedCategories()));
        try {
            String output = model.generate(prompt(candidate, sanitized.content()));
            EvalCaseDraft draft = parse(candidate, output);
            store.insertDraft(draft);
            store.updateCandidateStatus(candidateId, EvalCandidateStatus.DRAFT_READY);
            candidate.setStatus(EvalCandidateStatus.DRAFT_READY);
            return new Preparation(candidate.getStatus(), draft, sanitized.removedCategories());
        } catch (RuntimeException e) {
            return manual(candidate, "model_or_schema_failure");
        }
    }

    private Preparation manual(EvalCaseCandidate candidate, String reason) {
        store.updateCandidateStatus(candidate.getId(), EvalCandidateStatus.NEEDS_MANUAL_RECONSTRUCTION);
        candidate.setStatus(EvalCandidateStatus.NEEDS_MANUAL_RECONSTRUCTION);
        return new Preparation(candidate.getStatus(), null, List.of(reason));
    }

    private String prompt(EvalCaseCandidate candidate, String sanitizedContent) {
        return "Return JSON only with keys failure_summary, suspected_failure_family, suggested_case"
                + "{user_turns,initial_fixture_hint,expected_route,suggested_assertions},confidence,needs_human_review. "
                + "Never include production ids, identities, URLs, secrets, raw payloads, XML, or approval fields.\n"
                + "Failure family hint: " + candidate.getFailureFamily() + "\nSanitized evidence:\n" + sanitizedContent;
    }

    private EvalCaseDraft parse(EvalCaseCandidate candidate, String output) {
        try {
            if (PRODUCTION_ID.matcher(StringUtils.defaultString(output)).find()) throw new IllegalArgumentException("production id leaked");
            EvalDraftSanitizer.Result outputCheck = sanitizer.sanitize(List.of(output));
            if (!outputCheck.safeForModel() || !outputCheck.removedCategories().isEmpty()) throw new IllegalArgumentException("unsafe model output");
            JsonNode root = mapper.readTree(output);
            JsonNode suggested = root.path("suggested_case");
            validateKeys(root, Set.of("failure_summary", "suspected_failure_family", "suggested_case", "confidence", "needs_human_review"));
            validateKeys(suggested, Set.of("user_turns", "initial_fixture_hint", "expected_route", "suggested_assertions"));
            String family = text(root, "suspected_failure_family");
            String confidence = text(root, "confidence");
            if (!FAMILIES.contains(family) || !CONFIDENCE.contains(confidence)
                    || !root.path("needs_human_review").asBoolean(false)) {
                throw new IllegalArgumentException("invalid draft schema values");
            }
            List<String> turns = strings(suggested.path("user_turns"));
            List<String> assertions = strings(suggested.path("suggested_assertions"));
            if (turns.isEmpty() || assertions.isEmpty()) throw new IllegalArgumentException("draft is incomplete");
            return EvalCaseDraft.builder().id("ecd_" + UUID.randomUUID()).candidateId(candidate.getId())
                    .failureSummary(text(root, "failure_summary")).suspectedFailureFamily(family)
                    .userTurns(turns).initialFixtureHint(text(suggested, "initial_fixture_hint"))
                    .expectedRoute(text(suggested, "expected_route")).suggestedAssertions(assertions)
                    .confidence(confidence).needsHumanReview(true).sanitizerVersion(EvalDraftSanitizer.VERSION)
                    .modelVersion(model.version()).createdAt(clock.instant()).build();
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid Eval Draft output", e);
        }
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (StringUtils.isBlank(value)) throw new IllegalArgumentException("missing " + field);
        return value;
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asText).filter(StringUtils::isNotBlank).toList();
    }

    private void validateKeys(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw new IllegalArgumentException("draft section must be an object");
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new IllegalArgumentException("unexpected draft field: " + field);
        });
    }

    public record Preparation(EvalCandidateStatus status, EvalCaseDraft draft, List<String> sanitizerEvidence) { }
}
