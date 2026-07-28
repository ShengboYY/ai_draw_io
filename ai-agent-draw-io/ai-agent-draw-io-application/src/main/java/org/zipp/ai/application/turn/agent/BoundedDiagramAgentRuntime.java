package org.zipp.ai.application.turn.agent;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainGenerationRequest;
import org.zipp.ai.application.turn.TurnAttemptExecutionStatePort;
import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.context.AvailableContext;
import org.zipp.ai.application.turn.context.ContextRead;
import org.zipp.ai.application.turn.context.TruncatedContext;
import org.zipp.ai.application.turn.context.TrustedCanvasContext;
import org.zipp.ai.application.turn.skill.DiagramSkillBundle;
import org.zipp.ai.domain.retrieval.CancellationSignal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/** Code-owned loop that lets the model choose actions without granting authorization or commit. */
public final class BoundedDiagramAgentRuntime {

    private static final Pattern SAFE_OUTCOME_CODE = Pattern.compile("[A-Z0-9_]{3,80}");

    private final DiagramAgentDecisionPort decisions;
    private final DiagramAgentToolPort tools;
    private final DiagramDraftStore drafts;
    private final DiagramDraftVisualReviewPort visualReviews;
    private final TurnAttemptExecutionStatePort executionState;
    private final DiagramAgentBudget budget;
    private final PlainAgentTracePort trace;

    public BoundedDiagramAgentRuntime(
            DiagramAgentDecisionPort decisions,
            DiagramAgentToolPort tools,
            DiagramDraftStore drafts,
            DiagramDraftVisualReviewPort visualReviews,
            TurnAttemptExecutionStatePort executionState,
            DiagramAgentBudget budget,
            PlainAgentTracePort trace
    ) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.visualReviews = Objects.requireNonNull(visualReviews, "visualReviews");
        this.executionState = Objects.requireNonNull(executionState, "executionState");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    public BoundedDiagramAgentRuntime(
            DiagramAgentDecisionPort decisions,
            DiagramAgentToolPort tools,
            DiagramDraftStore drafts
    ) {
        this(
                decisions,
                tools,
                drafts,
                DiagramDraftVisualReviewPort.UNAVAILABLE,
                ignored -> new TurnAttemptExecutionStatePort.StateOutcome.Active(),
                DiagramAgentBudget.defaults(),
                PlainAgentTracePort.NOOP);
    }

    public BoundedDiagramAgentRuntime(
            DiagramAgentDecisionPort decisions,
            DiagramAgentToolPort tools,
            DiagramDraftStore drafts,
            DiagramDraftVisualReviewPort visualReviews
    ) {
        this(
                decisions,
                tools,
                drafts,
                visualReviews,
                ignored -> new TurnAttemptExecutionStatePort.StateOutcome.Active(),
                DiagramAgentBudget.defaults(),
                PlainAgentTracePort.NOOP);
    }

    public DiagramAgentRunResult run(
            PlainGenerationRequest request,
            DiagramSkillBundle skills,
            CancellationSignal cancellation
    ) {
        return run(request, skills, cancellation, ignored -> { });
    }

    public DiagramAgentRunResult run(
            PlainGenerationRequest request,
            DiagramSkillBundle skills,
            CancellationSignal cancellation,
            TurnEventSink events
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(skills, "skills");
        Objects.requireNonNull(events, "events");
        cancellation = cancellation == null ? CancellationSignal.NEVER : cancellation;
        requireSkillBinding(request, skills);
        long runStarted = System.nanoTime();
        int lastStepNumber = 0;
        trace(request.attempt(), 0, PlainAgentTraceType.AGENT_STARTED,
                request.plan().action().name(), "", "", "", "", "STARTED", 0, 0);
        publish(events, "plain_agent_started",
                progressPayload(0, request.plan().action().name(), "", "STARTED", 0, 0));

        try {
            DiagramAgentState state = initialState(request, skills);
            DiagramAgentToolRequest previousToolRequest = null;
            int repeatedActionCount = 0;
            while (true) {
                requireActive(request.attempt(), cancellation);
                if (state.stepCount() >= budget.maxSteps()) {
                    return submitBudgetFallback(state, cancellation, events, runStarted);
                }
                requireProgress(state);
                DiagramAgentObservation observation = observation(state);
                int stepNumber = state.stepCount() + 1;
                lastStepNumber = stepNumber;
                trace(request.attempt(), stepNumber, PlainAgentTraceType.DECISION_STARTED,
                        "DECIDE", "", "",
                        digest(state.activeDraft()), digest(state.activeDraft()),
                        "STARTED", visualIssueCount(state), 0);
                publish(events, "plain_agent_decision_started",
                        progressPayload(stepNumber, "DECIDE", "", "STARTED", 0,
                                visualIssueCount(state)));

                long decisionStarted = System.nanoTime();
                DiagramAgentAction action = Objects.requireNonNull(
                        decisions.decide(observation, cancellation),
                        "diagram agent action");
                long decisionLatency = elapsedMillis(decisionStarted);
                String actionType = action instanceof CallDiagramTool
                        ? "CALL_TOOL"
                        : "SUBMIT_CANDIDATE";
                String selectedTool = action instanceof CallDiagramTool call
                        ? call.request().toolName()
                        : "";
                trace(request.attempt(), stepNumber, PlainAgentTraceType.DECISION_SELECTED,
                        actionType,
                        selectedTool,
                        "", digest(state.activeDraft()), digest(state.activeDraft()),
                        "SELECTED", visualIssueCount(state), decisionLatency);
                publish(events, "plain_agent_decision_completed",
                        progressPayload(stepNumber, actionType, selectedTool, "SELECTED",
                                decisionLatency, visualIssueCount(state)));

                if (action instanceof SubmitDiagramCandidate submit) {
                    if (!hasCurrentVisualReview(state)
                            && state.visualReviewCount() < budget.maxVisualReviews()) {
                        state = reviewCurrentDraft(
                                state,
                                stepNumber,
                                "SUBMISSION_REVIEW",
                                cancellation,
                                events);
                        previousToolRequest = null;
                        repeatedActionCount = 0;
                        if (hasTerminalVisualReview(state)) {
                            return submit(
                                    state, submit, cancellation, stepNumber, events, runStarted);
                        }
                        // The submit decision consumed this Draw Agent step even though review
                        // delegated the draft back for one bounded repair.
                        state = deferSubmissionForRepair(state, stepNumber);
                        continue;
                    }
                    if (hasCurrentVisualReview(state)
                            && state.latestVisualReview().requestsRepair()
                            && state.mutationCount() < budget.maxMutations()
                            && state.visualReviewCount() < budget.maxVisualReviews()
                            && state.stepCount() + 1 < budget.maxSteps()) {
                        // A repairable review gets one more model decision instead of being ignored.
                        state = deferSubmissionForRepair(state, stepNumber);
                        previousToolRequest = null;
                        repeatedActionCount = 0;
                        continue;
                    }
                    return submit(state, submit, cancellation, stepNumber, events, runStarted);
                }

                DiagramAgentToolRequest toolRequest = ((CallDiagramTool) action).request();
                repeatedActionCount = toolRequest.equals(previousToolRequest)
                        ? repeatedActionCount + 1
                        : 0;
                if (repeatedActionCount >= budget.maxRepeatedActions()) {
                    throw stop("PLAIN_AGENT_REPEATED_ACTION");
                }
                previousToolRequest = toolRequest;
                authorize(state, toolRequest);
                DiagramAgentToolResult result = invokeTool(
                        state, toolRequest, stepNumber, "CALL_TOOL", cancellation, events);
                state = reduce(state, toolRequest, result, stepNumber);
                if (result.success() && (toolRequest instanceof CreateDraftRequest
                        || toolRequest instanceof PatchDraftRequest)) {
                    // Stream only an attempt-scoped preview. The outer write gate remains the
                    // sole path that can persist the final canvas.
                    DiagramDraftSnapshot preview = drafts.read(
                            request.attempt(), result.draft().ref());
                    publish(events, "plain_agent_draft_preview", preview.canvasXml());
                    state = reviewCurrentDraft(
                            state,
                            stepNumber,
                            "POST_MUTATION_REVIEW",
                            cancellation,
                            events);
                    if (hasTerminalVisualReview(state)) {
                        // The reviewer is a delegated agent with a code-owned terminal contract.
                        return submit(
                                state,
                                terminalReviewSubmission(state),
                                cancellation,
                                stepNumber,
                                events,
                                runStarted);
                    }
                }
            }
        } catch (RuntimeException failure) {
            trace(request.attempt(), lastStepNumber, PlainAgentTraceType.AGENT_STOPPED,
                    request.plan().action().name(), "", "", "", "",
                    safeCode(failure), 0, elapsedMillis(runStarted));
            throw failure;
        } finally {
            drafts.discard(request.attempt());
        }
    }

    private DiagramAgentState initialState(
            PlainGenerationRequest request,
            DiagramSkillBundle skills
    ) {
        DiagramDraftView initialDraft = null;
        List<String> digests = List.of();
        if (request.plan().action() != PlainDrawAction.CREATE) {
            TrustedCanvasContext canvas =
                    materialized(request.context().canvas(), TrustedCanvasContext.class);
            if (canvas == null || canvas.canvasXml().isBlank()) {
                throw new IllegalStateException("V2_PLAIN_CANVAS_REQUIRED");
            }
            DiagramDraftSnapshot snapshot = drafts.create(request.attempt(), canvas.canvasXml());
            initialDraft = DiagramDraftView.from(snapshot);
            digests = List.of(initialDraft.digest());
        }
        return new DiagramAgentState(
                request,
                skills,
                initialDraft,
                null,
                null,
                null,
                List.of(),
                digests,
                0,
                0,
                0,
                0,
                0,
                0);
    }

    private DiagramAgentRunResult submit(
            DiagramAgentState state,
            SubmitDiagramCandidate submit,
            CancellationSignal cancellation,
            int stepNumber,
            TurnEventSink events,
            long runStarted
    ) {
        if (state.activeDraft() == null
                || !state.activeDraft().ref().equals(submit.draftRef())
                || !state.activeDraft().digest().equals(submit.expectedDigest())) {
            throw stop("PLAIN_AGENT_SUBMISSION_STALE");
        }
        requireActive(state.request().attempt(), cancellation);
        // create/patch already passed the draft store's XML and reference-integrity checks.
        DiagramDraftSnapshot snapshot = drafts.read(
                state.request().attempt(), submit.draftRef());
        if (!snapshot.digest().equals(submit.expectedDigest())) {
            throw stop("PLAIN_AGENT_SUBMISSION_STALE");
        }
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.CANDIDATE_SUBMITTED,
                "SUBMIT_CANDIDATE", "", "",
                snapshot.digest(), snapshot.digest(), "CANDIDATE_SUBMITTED",
                visualIssueCount(state), 0);
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.AGENT_STOPPED,
                "", "", "", snapshot.digest(), snapshot.digest(),
                "CANDIDATE_SUBMITTED", visualIssueCount(state), elapsedMillis(runStarted));
        publish(events, "plain_agent_candidate_submitted", "");
        return new DiagramAgentRunResult(
                snapshot.ref(),
                snapshot.digest(),
                snapshot.canvasXml(),
                submit.assistantMessage(),
                stepNumber,
                state.mutationCount());
    }

    private DiagramAgentRunResult submitBudgetFallback(
            DiagramAgentState state,
            CancellationSignal cancellation,
            TurnEventSink events,
            long runStarted
    ) {
        if (state.activeDraft() == null) {
            throw stop("PLAIN_AGENT_STEP_BUDGET_EXHAUSTED");
        }
        requireActive(state.request().attempt(), cancellation);
        int stepNumber = state.stepCount();
        if (!hasCurrentVisualReview(state)
                && state.visualReviewCount() < budget.maxVisualReviews()) {
            // Exhausting model steps does not skip the protocol-owned final visual checkpoint.
            state = reviewCurrentDraft(
                    state,
                    stepNumber,
                    "BUDGET_FALLBACK_REVIEW",
                    cancellation,
                    events);
        }
        DiagramDraftSnapshot snapshot = drafts.read(
                state.request().attempt(), state.activeDraft().ref());
        if (!snapshot.digest().equals(state.activeDraft().digest())) {
            throw stop("PLAIN_AGENT_SUBMISSION_STALE");
        }
        // Budget exhaustion is a degraded success: the outer write gate still owns the real commit.
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.CANDIDATE_SUBMITTED,
                "BUDGET_FALLBACK", "", "",
                snapshot.digest(), snapshot.digest(), "CANDIDATE_SUBMITTED",
                visualIssueCount(state), 0);
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.AGENT_STOPPED,
                "BUDGET_FALLBACK", "", "",
                snapshot.digest(), snapshot.digest(), "CANDIDATE_SUBMITTED",
                visualIssueCount(state), elapsedMillis(runStarted));
        publish(events, "plain_agent_candidate_submitted", "");
        return new DiagramAgentRunResult(
                snapshot.ref(),
                snapshot.digest(),
                snapshot.canvasXml(),
                budgetFallbackMessage(),
                stepNumber,
                state.mutationCount());
    }

    private DiagramAgentState reduce(
            DiagramAgentState state,
            DiagramAgentToolRequest request,
            DiagramAgentToolResult result,
            int stepNumber
    ) {
        String beforeDigest = digest(state.activeDraft());
        String afterDigest = result.success() ? result.draft().digest() : beforeDigest;
        boolean mutation = request instanceof CreateDraftRequest
                || request instanceof PatchDraftRequest;
        int noProgress = state.noProgressCount();
        List<String> recentDigests = state.recentDraftDigests();
        if (!result.success()) {
            noProgress++;
        } else if (mutation) {
            if (afterDigest.equals(beforeDigest)) {
                noProgress++;
            } else {
                noProgress = 0;
                if (recentDigests.contains(afterDigest)) {
                    throw stop("PLAIN_AGENT_OSCILLATION_DETECTED");
                }
                ArrayList<String> updated = new ArrayList<>(recentDigests);
                updated.add(afterDigest);
                recentDigests = List.copyOf(updated);
            }
        }

        ArrayList<DiagramAgentStepRecord> steps = new ArrayList<>(state.steps());
        steps.add(new DiagramAgentStepRecord(
                stepNumber,
                "CALL_TOOL",
                request.toolName(),
                result.outcomeCode(),
                beforeDigest,
                afterDigest));
        DiagramAgentState updated = new DiagramAgentState(
                state.request(),
                state.skills(),
                result.success() ? result.draft() : state.activeDraft(),
                result.success() ? result.structure() : state.latestStructure(),
                latestVisualReview(state, request, result),
                result,
                steps,
                recentDigests,
                stepNumber,
                state.mutationCount() + (result.success() && mutation ? 1 : 0),
                state.createCallCount()
                        + (result.success() && request instanceof CreateDraftRequest ? 1 : 0),
                state.fullXmlInspectionCount()
                        + (result.success() && fullXmlInspection(request) ? 1 : 0),
                state.visualReviewCount(),
                noProgress);
        if (result.success() && mutation) {
            trace(state.request().attempt(), stepNumber, PlainAgentTraceType.DRAFT_UPDATED,
                    "CALL_TOOL", request.toolName(), "",
                    beforeDigest, afterDigest, "UPDATED",
                    0, 0);
        }
        return updated;
    }

    private DiagramDraftVisualReview latestVisualReview(
            DiagramAgentState state,
            DiagramAgentToolRequest request,
            DiagramAgentToolResult result
    ) {
        if (!result.success()) {
            return state.latestVisualReview();
        }
        if (request instanceof CreateDraftRequest || request instanceof PatchDraftRequest) {
            // A review is evidence about one immutable digest and is stale after every mutation.
            return null;
        }
        return state.latestVisualReview();
    }

    private DiagramAgentState deferSubmissionForRepair(
            DiagramAgentState state,
            int stepNumber
    ) {
        String digest = state.activeDraft().digest();
        ArrayList<DiagramAgentStepRecord> steps = new ArrayList<>(state.steps());
        steps.add(new DiagramAgentStepRecord(
                stepNumber,
                "SUBMIT_CANDIDATE",
                "",
                "VISUAL_REPAIR_REQUIRED",
                digest,
                digest));
        return new DiagramAgentState(
                state.request(),
                state.skills(),
                state.activeDraft(),
                state.latestStructure(),
                state.latestVisualReview(),
                state.latestToolResult(),
                steps,
                state.recentDraftDigests(),
                stepNumber,
                state.mutationCount(),
                state.createCallCount(),
                state.fullXmlInspectionCount(),
                state.visualReviewCount(),
                state.noProgressCount());
    }

    private DiagramAgentToolResult invokeTool(
            DiagramAgentState state,
            DiagramAgentToolRequest request,
            int stepNumber,
            String actionType,
            CancellationSignal cancellation,
            TurnEventSink events
    ) {
        String beforeDigest = digest(state.activeDraft());
        String argumentsDigest = ModelInputBinding.digestOf(request.toString());
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.TOOL_REQUESTED,
                actionType, request.toolName(), argumentsDigest,
                beforeDigest, beforeDigest, "REQUESTED",
                visualIssueCount(state), 0);
        publish(events, "plain_agent_tool_started",
                progressPayload(stepNumber, actionType, request.toolName(),
                        "REQUESTED", 0, visualIssueCount(state)));

        long started = System.nanoTime();
        DiagramAgentToolResult result = tools.execute(
                state.request().attempt(), state.request().plan(), request);
        long latency = elapsedMillis(started);
        requireActive(state.request().attempt(), cancellation);
        String afterDigest = result.success() ? result.draft().digest() : beforeDigest;
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.TOOL_COMPLETED,
                actionType, request.toolName(), argumentsDigest,
                beforeDigest, afterDigest, result.outcomeCode(),
                0, latency);
        publish(events, "plain_agent_tool_completed",
                progressPayload(stepNumber, actionType, request.toolName(),
                        result.outcomeCode(), latency, 0));
        return result;
    }

    private DiagramAgentState reviewCurrentDraft(
            DiagramAgentState state,
            int stepNumber,
            String actionType,
            CancellationSignal cancellation,
            TurnEventSink events
    ) {
        if (state.activeDraft() == null) {
            throw stop("PLAIN_AGENT_DRAFT_NOT_INITIALIZED");
        }
        if (hasCurrentVisualReview(state)) {
            return state;
        }
        if (state.visualReviewCount() >= budget.maxVisualReviews()) {
            throw stop("PLAIN_AGENT_VISUAL_REVIEW_BUDGET_EXHAUSTED");
        }
        requireActive(state.request().attempt(), cancellation);
        String digest = state.activeDraft().digest();
        DiagramDraftSnapshot snapshot = drafts.read(
                state.request().attempt(), state.activeDraft().ref());
        if (!digest.equals(snapshot.digest())) {
            throw stop("PLAIN_AGENT_DRAFT_DIGEST_STALE");
        }
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.VISUAL_REVIEW_STARTED,
                actionType, "visual_review_agent", "",
                digest, digest, "STARTED", 0, 0);
        publish(events, "plain_agent_visual_review_started",
                progressPayload(stepNumber, actionType, "visual_review_agent",
                        "STARTED", 0, 0));

        long started = System.nanoTime();
        DiagramDraftVisualReview review = Objects.requireNonNull(
                visualReviews.review(state.request().plan(), snapshot),
                "visual review");
        long latency = elapsedMillis(started);
        requireActive(state.request().attempt(), cancellation);
        if (!review.reviews(state.activeDraft())) {
            throw stop("PLAIN_AGENT_VISUAL_REVIEW_STALE");
        }
        review = boundRepairToRemainingBudget(state, review, stepNumber);
        trace(state.request().attempt(), stepNumber,
                PlainAgentTraceType.VISUAL_REVIEW_COMPLETED,
                actionType, "visual_review_agent", "",
                digest, digest, review.decision(), review.issues().size(), latency);
        publish(events, "plain_agent_visual_review_completed",
                visualReviewProgressPayload(
                        stepNumber, actionType, review, latency));

        ArrayList<DiagramAgentStepRecord> steps = new ArrayList<>(state.steps());
        steps.add(new DiagramAgentStepRecord(
                stepNumber,
                "DELEGATE_AGENT",
                "visual_review_agent",
                review.decision(),
                digest,
                digest));
        return new DiagramAgentState(
                state.request(),
                state.skills(),
                state.activeDraft(),
                state.latestStructure(),
                review,
                state.latestToolResult(),
                steps,
                state.recentDraftDigests(),
                state.stepCount(),
                state.mutationCount(),
                state.createCallCount(),
                state.fullXmlInspectionCount(),
                state.visualReviewCount() + 1,
                state.noProgressCount());
    }

    private DiagramDraftVisualReview boundRepairToRemainingBudget(
            DiagramAgentState state,
            DiagramDraftVisualReview review,
            int completedDrawStep
    ) {
        boolean canRepair = completedDrawStep < budget.maxSteps()
                && state.mutationCount() < budget.maxMutations()
                && state.visualReviewCount() + 1 < budget.maxVisualReviews();
        if (!review.requestsRepair() || canRepair) {
            return review;
        }
        // A repair that cannot be reviewed again must not authorize an unreviewed mutation.
        return new DiagramDraftVisualReview(
                review.reviewedDigest(),
                "NEEDS_HUMAN_REVIEW",
                review.available(),
                review.summary(),
                review.issues(),
                review.groundingConflict(),
                review.reviewerVersion());
    }

    private boolean hasCurrentVisualReview(DiagramAgentState state) {
        return state.latestVisualReview() != null
                && state.latestVisualReview().reviews(state.activeDraft());
    }

    private boolean hasTerminalVisualReview(DiagramAgentState state) {
        return hasCurrentVisualReview(state)
                && !state.latestVisualReview().requestsRepair();
    }

    private SubmitDiagramCandidate terminalReviewSubmission(DiagramAgentState state) {
        return new SubmitDiagramCandidate(
                state.activeDraft().ref(),
                state.activeDraft().digest(),
                terminalReviewMessage(state));
    }

    private String terminalReviewMessage(DiagramAgentState state) {
        String decision = state.latestVisualReview().decision();
        boolean chinese = state.request().context().request().instruction().value()
                .codePoints()
                .anyMatch(codePoint -> codePoint >= 0x3400 && codePoint <= 0x9FFF);
        if (chinese) {
            return switch (decision) {
                case "APPROVE" -> "图表已生成并通过视觉审阅。";
                case "APPROVE_WITH_NOTES" -> "图表已生成并通过视觉审阅，仍保留少量备注。";
                case "NEEDS_HUMAN_REVIEW" ->
                        "图表已生成并完成视觉审阅；仍有需要人工确认的问题，我已保留当前版本。";
                default -> "图表已生成；视觉审阅暂时不可用，我已保留当前版本。";
            };
        }
        return switch (decision) {
            case "APPROVE" -> "The diagram was generated and passed visual review.";
            case "APPROVE_WITH_NOTES" ->
                    "The diagram was generated and passed visual review with notes.";
            case "NEEDS_HUMAN_REVIEW" ->
                    "The diagram was generated and preserved for human review.";
            default ->
                    "The diagram was generated and preserved because visual review was unavailable.";
        };
    }

    private void authorize(DiagramAgentState state, DiagramAgentToolRequest request) {
        if (!observableTools(state).contains(request.toolName())) {
            throw stop("PLAIN_AGENT_TOOL_NOT_ALLOWED");
        }
        if (request instanceof CreateDraftRequest) {
            if (state.activeDraft() != null
                    || state.createCallCount() >= budget.maxCreateCalls()
                    || state.mutationCount() >= budget.maxMutations()) {
                throw stop("PLAIN_AGENT_CREATE_NOT_ALLOWED");
            }
            return;
        }
        if (state.activeDraft() == null) {
            throw stop("PLAIN_AGENT_DRAFT_NOT_INITIALIZED");
        }
        DraftRef target = request instanceof PatchDraftRequest patch
                ? patch.draftRef()
                : ((InspectDraftRequest) request).draftRef();
        if (!state.activeDraft().ref().equals(target)) {
            throw stop("PLAIN_AGENT_DRAFT_REF_STALE");
        }
        if (request instanceof PatchDraftRequest patch) {
            authorizeVisualRepair(state, patch);
            if (state.mutationCount() >= budget.maxMutations()) {
                throw stop("PLAIN_AGENT_MUTATION_BUDGET_EXHAUSTED");
            }
        }
        if (fullXmlInspection(request)
                && state.fullXmlInspectionCount() >= budget.maxFullXmlInspections()) {
            throw stop("PLAIN_AGENT_FULL_XML_BUDGET_EXHAUSTED");
        }
    }

    private void authorizeVisualRepair(
            DiagramAgentState state,
            PatchDraftRequest patch
    ) {
        if (!hasCurrentVisualReview(state)
                || !state.latestVisualReview().requestsRepair()) {
            return;
        }
        Set<String> allowedTargets = new LinkedHashSet<>();
        for (DiagramDraftVisualIssue issue : state.latestVisualReview().issues()) {
            allowedTargets.addAll(issue.targetCellIds());
        }
        boolean unauthorized = allowedTargets.isEmpty()
                || patch.mutations().stream().anyMatch(mutation ->
                mutation.type() != DraftMutationType.REPLACE
                        || !allowedTargets.contains(mutation.cellId()));
        if (unauthorized) {
            // Visual feedback authorizes only local replacement of explicitly grounded cells.
            throw stop("PLAIN_AGENT_REPAIR_TARGET_NOT_ALLOWED");
        }
    }

    private DiagramAgentObservation observation(DiagramAgentState state) {
        return new DiagramAgentObservation(
                state,
                observableTools(state),
                budget.maxSteps() - state.stepCount(),
                budget.maxMutations() - state.mutationCount(),
                budget.maxFullXmlInspections() - state.fullXmlInspectionCount(),
                budget.maxVisualReviews() - state.visualReviewCount());
    }

    private List<String> observableTools(DiagramAgentState state) {
        ArrayList<String> tools = new ArrayList<>(
                allowedTools(state.request().plan().action()));
        if (state.activeDraft() != null) {
            tools.remove("create_draft");
        }
        if (state.mutationCount() >= budget.maxMutations()
                || (hasCurrentVisualReview(state)
                && !state.latestVisualReview().requestsRepair())) {
            // Do not create a digest that cannot pass through another visual checkpoint.
            tools.remove("patch_draft");
        }
        return List.copyOf(tools);
    }

    private List<String> allowedTools(PlainDrawAction action) {
        return action == PlainDrawAction.CREATE
                ? List.of("create_draft", "inspect_draft", "patch_draft")
                : List.of("inspect_draft", "patch_draft");
    }

    private void requireProgress(DiagramAgentState state) {
        if (state.noProgressCount() >= budget.maxNoProgressSteps()) {
            throw stop("PLAIN_AGENT_NO_PROGRESS");
        }
    }

    private String budgetFallbackMessage() {
        return "The diagram was generated from the latest validated draft "
                + "after the agent step budget was exhausted.";
    }

    private void requireActive(FencedAttempt attempt, CancellationSignal cancellation) {
        if (cancellation.isCancelled()) {
            throw new CancellationException("TURN_EXECUTION_CANCELLED");
        }
        TurnAttemptExecutionStatePort.StateOutcome outcome = executionState.check(attempt);
        if (outcome instanceof TurnAttemptExecutionStatePort.StateOutcome.Active) {
            return;
        }
        if (outcome instanceof TurnAttemptExecutionStatePort.StateOutcome.Unavailable unavailable) {
            throw stop(unavailable.code());
        }
        throw stop("PLAIN_AGENT_ATTEMPT_STALE");
    }

    private void requireSkillBinding(
            PlainGenerationRequest request,
            DiagramSkillBundle skills
    ) {
        if (!request.plan().skillSelection().bindingDigest()
                .equals(skills.selectionBindingDigest())) {
            throw new IllegalStateException("V2_SKILL_BUNDLE_MISMATCH");
        }
    }

    private boolean fullXmlInspection(DiagramAgentToolRequest request) {
        return request instanceof InspectDraftRequest inspect
                && inspect.scope() == DraftInspectionScope.FULL_XML;
    }

    private int visualIssueCount(DiagramAgentState state) {
        return state.latestVisualReview() == null
                ? 0
                : state.latestVisualReview().issues().size();
    }

    private String digest(DiagramDraftView draft) {
        return draft == null ? "" : draft.digest();
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private String progressPayload(
            int step,
            String action,
            String tool,
            String outcome,
            long latencyMillis,
            int issues
    ) {
        // Fields are code-owned enums/numbers, so a tab-delimited payload stays dependency-free
        // in the application module and can be safely projected by transport adapters.
        return step + "\t" + action + "\t" + tool + "\t" + outcome
                + "\t" + latencyMillis + "\t" + issues;
    }

    private String visualReviewProgressPayload(
            int step,
            String action,
            DiagramDraftVisualReview review,
            long latencyMillis
    ) {
        // UI feedback is bounded and encoded so model text cannot break the tab-delimited envelope.
        String feedback = review.issues().stream()
                .limit(3)
                .map(this::visualIssueFeedback)
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return progressPayload(
                step,
                action,
                "visual_review_agent",
                review.decision(),
                latencyMillis,
                review.issues().size())
                + "\t" + encodeProgressText(review.summary())
                + "\t" + encodeProgressText(feedback);
    }

    private String visualIssueFeedback(DiagramDraftVisualIssue issue) {
        String evidence = boundedProgressText(issue.evidence(), 400);
        String repair = boundedProgressText(issue.repairInstruction(), 400);
        if (!evidence.isBlank() && !repair.isBlank()) {
            return evidence + " " + repair;
        }
        if (!evidence.isBlank()) {
            return evidence;
        }
        if (!repair.isBlank()) {
            return repair;
        }
        return boundedProgressText(issue.type(), 120);
    }

    private String encodeProgressText(String value) {
        String bounded = boundedProgressText(value, 1_200);
        if (bounded.isBlank()) {
            return "";
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bounded.getBytes(StandardCharsets.UTF_8));
    }

    private String boundedProgressText(String value, int maxLength) {
        String safe = value == null
                ? ""
                : value.replace('\t', ' ')
                        .replace('\r', ' ')
                        .replace('\n', ' ')
                        .trim();
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }

    private void publish(TurnEventSink events, String type, String payload) {
        try {
            events.publish(new TurnEvent(type, payload, Instant.now()));
        } catch (RuntimeException ignored) {
            // A detached browser must never change the attempt outcome.
        }
    }

    private IllegalStateException stop(String code) {
        return new IllegalStateException(code);
    }

    private String safeCode(RuntimeException failure) {
        String message = failure.getMessage();
        // Trace stores stable codes only; arbitrary exception text may contain model or XML content.
        return message != null && SAFE_OUTCOME_CODE.matcher(message).matches()
                ? message
                : "PLAIN_AGENT_RUNTIME_FAILED";
    }

    private void trace(
            FencedAttempt attempt,
            int step,
            PlainAgentTraceType type,
            String action,
            String tool,
            String argumentsDigest,
            String beforeDigest,
            String afterDigest,
            String outcome,
            int issues,
            long latency
    ) {
        trace.recordSafely(new PlainAgentTraceEvent(
                attempt,
                step,
                type,
                action,
                tool,
                argumentsDigest,
                beforeDigest,
                afterDigest,
                outcome,
                issues,
                latency,
                Instant.now()));
    }

    private <T> T materialized(ContextRead<T> read, Class<T> type) {
        Object value = null;
        if (read instanceof AvailableContext<?> available) {
            value = available.value();
        } else if (read instanceof TruncatedContext<?> truncated) {
            value = truncated.value();
        }
        return type.isInstance(value) ? type.cast(value) : null;
    }

}
