package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.*;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.evaluation.intake.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class TraceToEvalDraftServiceTest {

    @Test
    public void shouldSanitizeBeforeModelAndPersistSchemaValidatedDraft() {
        MemoryStore store = new MemoryStore();
        store.candidate = candidate();
        RecordingModel model = new RecordingModel(validDraftJson());
        TraceToEvalDraftService service = new TraceToEvalDraftService(store,
                debugService("Contact alice@example.com at https://internal.example and token=sk-secret12345"), model);

        TraceToEvalDraftService.Preparation result = service.prepare("candidate-1", "admin-1", true, "ip", "ua");

        assertEquals(EvalCandidateStatus.DRAFT_READY, result.status());
        assertNotNull(result.draft());
        assertTrue(result.draft().isNeedsHumanReview());
        assertFalse(model.prompt.contains("alice@example.com"));
        assertFalse(model.prompt.contains("internal.example"));
        assertFalse(model.prompt.contains("sk-secret"));
        assertFalse(model.prompt.contains("run-private"));
        assertEquals(1, store.drafts.size());
    }

    @Test
    public void shouldFailClosedForDiagramXmlWithoutCallingModel() {
        MemoryStore store = new MemoryStore();
        store.candidate = candidate();
        RecordingModel model = new RecordingModel(validDraftJson());
        TraceToEvalDraftService service = new TraceToEvalDraftService(store,
                debugService("<mxGraphModel><root/></mxGraphModel>"), model);

        TraceToEvalDraftService.Preparation result = service.prepare("candidate-1", "admin-1", true, "ip", "ua");

        assertEquals(EvalCandidateStatus.NEEDS_MANUAL_RECONSTRUCTION, result.status());
        assertNull(result.draft());
        assertNull(model.prompt);
        assertTrue(store.drafts.isEmpty());
    }

    @Test
    public void shouldRejectModelOutputThatLeaksAProductionIdentifier() {
        MemoryStore store = new MemoryStore();
        store.candidate = candidate();
        RecordingModel model = new RecordingModel(validDraftJson().replace("Mutation failed", "Mutation failed in aru_private_1"));
        TraceToEvalDraftService service = new TraceToEvalDraftService(store, debugService("Safe synthetic evidence"), model);

        TraceToEvalDraftService.Preparation result = service.prepare("candidate-1", "admin-1", true, "ip", "ua");

        assertEquals(EvalCandidateStatus.NEEDS_MANUAL_RECONSTRUCTION, result.status());
        assertTrue(store.drafts.isEmpty());
    }

    @Test
    public void shouldReplaceBusinessEntitiesWithStablePlaceholdersBeforeTheModel() {
        MemoryStore store = new MemoryStore();
        store.candidate = candidate();
        store.candidate.setEvidenceSummary("Company: Acme Corp reported a mutation failure");
        RecordingModel model = new RecordingModel(validDraftJson());
        TraceToEvalDraftService service = new TraceToEvalDraftService(store,
                debugService("Customer: Northwind Corp, Product: LedgerPro and Service: BillingEdge. Customer: Northwind Corp."), model);

        TraceToEvalDraftService.Preparation result = service.prepare("candidate-1", "admin-1", true, "ip", "ua");

        assertEquals(EvalCandidateStatus.DRAFT_READY, result.status());
        assertTrue(model.prompt.contains("[COMPANY-1]"));
        assertTrue(model.prompt.contains("[CUSTOMER-1]"));
        assertTrue(model.prompt.contains("[PRODUCT-1]"));
        assertTrue(model.prompt.contains("[INTERNAL-SERVICE-1]"));
        assertEquals(2, occurrences(model.prompt, "[CUSTOMER-1]"));
        assertFalse(model.prompt.contains("Acme Corp"));
        assertFalse(model.prompt.contains("Northwind Corp"));
        assertFalse(model.prompt.contains("LedgerPro"));
        assertFalse(model.prompt.contains("BillingEdge"));
    }

    @Test
    public void shouldRejectModelOutputThatReintroducesABusinessEntity() {
        MemoryStore store = new MemoryStore();
        store.candidate = candidate();
        RecordingModel model = new RecordingModel(validDraftJson().replace("Mutation failed", "Customer: Northwind Corp"));
        TraceToEvalDraftService service = new TraceToEvalDraftService(store, debugService("Safe synthetic evidence"), model);

        TraceToEvalDraftService.Preparation result = service.prepare("candidate-1", "admin-1", true, "ip", "ua");

        assertEquals(EvalCandidateStatus.NEEDS_MANUAL_RECONSTRUCTION, result.status());
        assertTrue(store.drafts.isEmpty());
    }

    @Test
    public void unstructuredConfidentialTextMustFailClosedWithoutCallingTheModel() {
        MemoryStore store = new MemoryStore();
        store.candidate = candidate();
        RecordingModel model = new RecordingModel(validDraftJson());
        TraceToEvalDraftService service = new TraceToEvalDraftService(store,
                debugService("Contains unredacted confidential architecture notes"), model);

        TraceToEvalDraftService.Preparation result = service.prepare("candidate-1", "admin-1", true, "ip", "ua");

        assertEquals(EvalCandidateStatus.NEEDS_MANUAL_RECONSTRUCTION, result.status());
        assertNull(model.prompt);
    }

    private int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }

    private EvalCaseCandidate candidate() {
        return EvalCaseCandidate.builder().id("candidate-1").sourceRunId("run-private")
                .failureFamily("artifact").evidenceSummary("Canvas mutation failed")
                .status(EvalCandidateStatus.TRIAGED).build();
    }

    private AgentDebugTraceService debugService(String content) {
        return new AgentDebugTraceService(null, null) {
            @Override public List<DebugTraceCapture> viewCapturesForRun(String actor, String run, String ip, String ua) {
                return List.of(DebugTraceCapture.builder().id("capture-1").content(content)
                        .contentExpiresAt(Instant.now().plusSeconds(60)).build());
            }
        };
    }

    private String validDraftJson() {
        return "{\"failure_summary\":\"Mutation failed\",\"suspected_failure_family\":\"artifact\","
                + "\"suggested_case\":{\"user_turns\":[\"Add a synthetic worker\"],"
                + "\"initial_fixture_hint\":\"synthetic architecture\",\"expected_route\":\"edit_existing\","
                + "\"suggested_assertions\":[\"worker exists\"]},\"confidence\":\"medium\",\"needs_human_review\":true}";
    }

    private static final class RecordingModel implements IEvalDraftModel {
        private final String output; private String prompt;
        private RecordingModel(String output) { this.output = output; }
        @Override public String generate(String prompt) { this.prompt = prompt; return output; }
        @Override public String version() { return "fake-v1"; }
    }

    private static final class MemoryStore implements ITraceToEvalStore {
        private EvalCaseCandidate candidate;
        private final List<EvalCaseDraft> drafts = new ArrayList<>();
        @Override public Optional<EvalCaseCandidate> findCandidate(String id) { return Optional.ofNullable(candidate); }
        @Override public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String run, String family) { return Optional.empty(); }
        @Override public void insertCandidate(EvalCaseCandidate value) { candidate = value; }
        @Override public void updateCandidateStatus(String id, EvalCandidateStatus status) { candidate.setStatus(status); }
        @Override public void insertReview(EvalCaseReview review) { }
        @Override public void insertDraft(EvalCaseDraft draft) { drafts.add(draft); }
        @Override public void insertLineage(EvalCaseLineage lineage) { }
    }
}
