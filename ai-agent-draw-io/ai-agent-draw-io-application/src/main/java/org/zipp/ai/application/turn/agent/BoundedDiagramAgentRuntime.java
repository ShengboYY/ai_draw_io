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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/** Code-owned loop that lets the model choose actions without granting authorization or commit. */
public final class BoundedDiagramAgentRuntime {

    private static final Pattern SAFE_OUTCOME_CODE = Pattern.compile("[A-Z0-9_]{3,80}");

    private final DiagramAgentDecisionPort decisions;
    private final DiagramAgentToolPort tools;
    private final DiagramDraftStore drafts;
    private final TurnAttemptExecutionStatePort executionState;
    private final DiagramAgentBudget budget;
    private final PlainAgentTracePort trace;

    public BoundedDiagramAgentRuntime(
            DiagramAgentDecisionPort decisions,
            DiagramAgentToolPort tools,
            DiagramDraftStore drafts,
            TurnAttemptExecutionStatePort executionState,
            DiagramAgentBudget budget,
            PlainAgentTracePort trace
    ) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.drafts = Objects.requireNonNull(drafts, "drafts");
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
        trace(request.attempt(), 0, PlainAgentTraceType.AGENT_STARTED,
                "", "", "", "", "", "STARTED", 0, 0);
        trace(request.attempt(), 0, PlainAgentTraceType.SKILLS_LOADED,
                "", "", skills.selectionBindingDigest(), "", "", "SUCCESS", 0, 0);
        publish(events, "plain_agent_started",
                progressPayload(0, "START", "", "STARTED", 0, 0));
        publish(events, "plain_agent_skills_loaded",
                Integer.toString(skills.orderedSkills().size()));

        try {
            DiagramAgentState state = initialState(request, skills);
            DiagramAgentToolRequest previousToolRequest = null;
            int repeatedActionCount = 0;
            while (true) {
                requireActive(request.attempt(), cancellation);
                if (state.stepCount() >= budget.maxSteps()) {
                    return submitBudgetFallback(state, cancellation, events);
                }
                requireProgress(state);
                DiagramAgentObservation observation = observation(state);
                int stepNumber = state.stepCount() + 1;
                publish(events, "plain_agent_decision_started",
                        progressPayload(stepNumber, "DECIDE", "", "STARTED", 0,
                                issueCount(state.latestAnalysis())));

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
                        "SELECTED", issueCount(state.latestAnalysis()), decisionLatency);
                publish(events, "plain_agent_decision_completed",
                        progressPayload(stepNumber, actionType, selectedTool, "SELECTED",
                                decisionLatency, issueCount(state.latestAnalysis())));

                if (action instanceof SubmitDiagramCandidate submit) {
                    return submit(state, submit, cancellation, stepNumber, events);
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
                String beforeDigest = digest(state.activeDraft());
                String argumentsDigest = ModelInputBinding.digestOf(toolRequest.toString());
                trace(request.attempt(), stepNumber, PlainAgentTraceType.TOOL_REQUESTED,
                        "CALL_TOOL", toolRequest.toolName(), argumentsDigest,
                        beforeDigest, beforeDigest, "REQUESTED",
                        issueCount(state.latestAnalysis()), 0);
                publish(events, "plain_agent_tool_started",
                        progressPayload(stepNumber, "CALL_TOOL", toolRequest.toolName(),
                                "REQUESTED", 0, issueCount(state.latestAnalysis())));

                long toolStarted = System.nanoTime();
                DiagramAgentToolResult result = tools.execute(
                        request.attempt(), request.plan(), toolRequest);
                long toolLatency = elapsedMillis(toolStarted);
                requireActive(request.attempt(), cancellation);
                String afterDigest = result.success() ? result.draft().digest() : beforeDigest;
                trace(request.attempt(), stepNumber, PlainAgentTraceType.TOOL_COMPLETED,
                        "CALL_TOOL", toolRequest.toolName(), argumentsDigest,
                        beforeDigest, afterDigest, result.outcomeCode(),
                        issueCount(result.analysis()), toolLatency);
                publish(events, "plain_agent_tool_completed",
                        progressPayload(stepNumber, "CALL_TOOL", toolRequest.toolName(),
                                result.outcomeCode(), toolLatency,
                                issueCount(result.analysis())));

                state = reduce(state, toolRequest, result, stepNumber);
                if (result.success() && (toolRequest instanceof CreateDraftRequest
                        || toolRequest instanceof PatchDraftRequest)) {
                    // Stream only an attempt-scoped preview. The outer write gate remains the
                    // sole path that can persist the final canvas.
                    DiagramDraftSnapshot preview = drafts.read(
                            request.attempt(), result.draft().ref());
                    publish(events, "plain_agent_draft_preview", preview.canvasXml());
                }
            }
        } catch (RuntimeException failure) {
            trace(request.attempt(), 0, PlainAgentTraceType.AGENT_STOPPED,
                    "", "", "", "", "", safeCode(failure), 0, 0);
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
                List.of(),
                digests,
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
            TurnEventSink events
    ) {
        if (state.activeDraft() == null
                || !state.activeDraft().ref().equals(submit.draftRef())
                || !state.activeDraft().digest().equals(submit.expectedDigest())) {
            throw stop("PLAIN_AGENT_SUBMISSION_STALE");
        }
        requireActive(state.request().attempt(), cancellation);
        InspectDraftRequest validationRequest = new InspectDraftRequest(
                submit.draftRef(),
                DraftInspectionScope.SUMMARY,
                List.of(),
                "");
        String validationArgumentsDigest = ModelInputBinding.digestOf(validationRequest.toString());
        String draftDigest = state.activeDraft().digest();
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.TOOL_REQUESTED,
                "FINAL_VALIDATION", validationRequest.toolName(), validationArgumentsDigest,
                draftDigest, draftDigest, "REQUESTED",
                issueCount(state.latestAnalysis()), 0);
        publish(events, "plain_agent_tool_started",
                progressPayload(stepNumber, "FINAL_VALIDATION", validationRequest.toolName(),
                        "REQUESTED", 0, issueCount(state.latestAnalysis())));
        long validationStarted = System.nanoTime();
        DiagramAgentToolResult validation = tools.execute(
                state.request().attempt(),
                state.request().plan(),
                validationRequest);
        long validationLatency = elapsedMillis(validationStarted);
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.TOOL_COMPLETED,
                "FINAL_VALIDATION", validationRequest.toolName(), validationArgumentsDigest,
                draftDigest, validation.success() ? validation.draft().digest() : draftDigest,
                validation.outcomeCode(), issueCount(validation.analysis()), validationLatency);
        publish(events, "plain_agent_tool_completed",
                progressPayload(stepNumber, "FINAL_VALIDATION", validationRequest.toolName(),
                        validation.outcomeCode(), validationLatency,
                        issueCount(validation.analysis())));
        if (!validation.success() || validation.analysis() == null
                || !validation.analysis().readyForSubmission()) {
            throw stop("PLAIN_AGENT_FINAL_VALIDATION_FAILED");
        }
        DiagramDraftSnapshot snapshot = drafts.read(
                state.request().attempt(), submit.draftRef());
        if (!snapshot.digest().equals(submit.expectedDigest())) {
            throw stop("PLAIN_AGENT_SUBMISSION_STALE");
        }
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.CANDIDATE_SUBMITTED,
                "SUBMIT_CANDIDATE", "", "",
                snapshot.digest(), snapshot.digest(), "CANDIDATE_SUBMITTED",
                validation.analysis().issues().size(), 0);
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.AGENT_STOPPED,
                "", "", "", snapshot.digest(), snapshot.digest(),
                "CANDIDATE_SUBMITTED", validation.analysis().issues().size(), 0);
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
            TurnEventSink events
    ) {
        if (state.activeDraft() == null) {
            throw stop("PLAIN_AGENT_STEP_BUDGET_EXHAUSTED");
        }
        requireActive(state.request().attempt(), cancellation);
        int stepNumber = state.stepCount();
        InspectDraftRequest validationRequest = new InspectDraftRequest(
                state.activeDraft().ref(),
                DraftInspectionScope.SUMMARY,
                List.of(),
                "");
        String validationArgumentsDigest = ModelInputBinding.digestOf(validationRequest.toString());
        String draftDigest = state.activeDraft().digest();
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.TOOL_REQUESTED,
                "BUDGET_FALLBACK_VALIDATION", validationRequest.toolName(),
                validationArgumentsDigest, draftDigest, draftDigest, "REQUESTED",
                issueCount(state.latestAnalysis()), 0);
        publish(events, "plain_agent_tool_started",
                progressPayload(stepNumber, "BUDGET_FALLBACK_VALIDATION",
                        validationRequest.toolName(), "REQUESTED", 0,
                        issueCount(state.latestAnalysis())));
        long validationStarted = System.nanoTime();
        DiagramAgentToolResult validation = tools.execute(
                state.request().attempt(),
                state.request().plan(),
                validationRequest);
        long validationLatency = elapsedMillis(validationStarted);
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.TOOL_COMPLETED,
                "BUDGET_FALLBACK_VALIDATION", validationRequest.toolName(),
                validationArgumentsDigest, draftDigest,
                validation.success() ? validation.draft().digest() : draftDigest,
                validation.outcomeCode(), issueCount(validation.analysis()), validationLatency);
        publish(events, "plain_agent_tool_completed",
                progressPayload(stepNumber, "BUDGET_FALLBACK_VALIDATION",
                        validationRequest.toolName(), validation.outcomeCode(),
                        validationLatency, issueCount(validation.analysis())));
        if (!validation.success() || validation.analysis() == null
                || !validation.analysis().structurallyValid()) {
            throw stop("PLAIN_AGENT_BUDGET_FALLBACK_INVALID");
        }

        DiagramDraftSnapshot snapshot = drafts.read(
                state.request().attempt(), state.activeDraft().ref());
        if (!snapshot.digest().equals(state.activeDraft().digest())) {
            throw stop("PLAIN_AGENT_SUBMISSION_STALE");
        }
        int remainingIssues = validation.analysis().issues().size();
        // Budget exhaustion is a degraded success: the outer write gate still owns the real commit.
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.CANDIDATE_SUBMITTED,
                "BUDGET_FALLBACK", "", "",
                snapshot.digest(), snapshot.digest(), "CANDIDATE_SUBMITTED",
                remainingIssues, 0);
        trace(state.request().attempt(), stepNumber, PlainAgentTraceType.AGENT_STOPPED,
                "BUDGET_FALLBACK", "", "",
                snapshot.digest(), snapshot.digest(), "CANDIDATE_SUBMITTED",
                remainingIssues, 0);
        return new DiagramAgentRunResult(
                snapshot.ref(),
                snapshot.digest(),
                snapshot.canvasXml(),
                budgetFallbackMessage(remainingIssues),
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
                result.success() ? result.analysis() : state.latestAnalysis(),
                result,
                steps,
                recentDigests,
                stepNumber,
                state.mutationCount() + (result.success() && mutation ? 1 : 0),
                state.createCallCount()
                        + (result.success() && request instanceof CreateDraftRequest ? 1 : 0),
                state.fullXmlInspectionCount()
                        + (result.success() && fullXmlInspection(request) ? 1 : 0),
                noProgress);
        if (result.success() && mutation) {
            trace(state.request().attempt(), stepNumber, PlainAgentTraceType.DRAFT_UPDATED,
                    "CALL_TOOL", request.toolName(), "",
                    beforeDigest, afterDigest, "UPDATED",
                    issueCount(result.analysis()), 0);
        }
        if (result.success()) {
            trace(state.request().attempt(), stepNumber, PlainAgentTraceType.ANALYSIS_COMPLETED,
                    "CALL_TOOL", request.toolName(), "",
                    beforeDigest, afterDigest,
                    result.analysis().readyForSubmission() ? "READY" : "ISSUES_FOUND",
                    result.analysis().issues().size(), 0);
        }
        return updated;
    }

    private void authorize(DiagramAgentState state, DiagramAgentToolRequest request) {
        if (!allowedTools(state.request().plan().action()).contains(request.toolName())) {
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
        if (request instanceof PatchDraftRequest
                && state.mutationCount() >= budget.maxMutations()) {
            throw stop("PLAIN_AGENT_MUTATION_BUDGET_EXHAUSTED");
        }
        if (fullXmlInspection(request)
                && state.fullXmlInspectionCount() >= budget.maxFullXmlInspections()) {
            throw stop("PLAIN_AGENT_FULL_XML_BUDGET_EXHAUSTED");
        }
    }

    private DiagramAgentObservation observation(DiagramAgentState state) {
        return new DiagramAgentObservation(
                state,
                allowedTools(state.request().plan().action()),
                budget.maxSteps() - state.stepCount(),
                budget.maxMutations() - state.mutationCount(),
                budget.maxFullXmlInspections() - state.fullXmlInspectionCount());
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

    private String budgetFallbackMessage(int issueCount) {
        String message = "The diagram was generated from the latest structurally valid draft "
                + "after the agent step budget was exhausted.";
        return issueCount == 0
                ? message
                : message + " " + issueCount + " automated check(s) remain for review.";
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

    private int issueCount(DiagramDraftAnalysis analysis) {
        return analysis == null ? 0 : analysis.issues().size();
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
