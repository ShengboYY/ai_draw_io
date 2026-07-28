package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.agent.DraftCellMutation;
import org.zipp.ai.infrastructure.turn.agent.InMemoryDiagramDraftStore;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryDiagramDraftStoreTest {

    @Test
    void createsImmutableVersionsAndAppliesOnlyDeclaredCellPatches() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        FencedAttempt attempt = attempt("attempt-1", 1);
        var initial = store.create(attempt, graph(
                "<mxCell id=\"node-a\" value=\"A\" vertex=\"1\" parent=\"1\">"
                        + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/>"
                        + "</mxCell>"));

        var patched = store.patch(
                attempt,
                initial.ref(),
                initial.digest(),
                List.of(DraftCellMutation.replace(
                        "node-a",
                        "<mxCell id=\"node-a\" value=\"B\" vertex=\"1\" parent=\"1\">"
                                + "<mxGeometry x=\"20\" y=\"20\" width=\"80\" height=\"40\" as=\"geometry\"/>"
                                + "</mxCell>")));

        assertThat(patched.draft().version()).isEqualTo(2);
        assertThat(patched.changedCellIds()).containsExactly("node-a");
        assertThat(patched.draft().canvasXml()).contains("value=\"B\"");
        assertThat(store.read(attempt, initial.ref()).canvasXml()).contains("value=\"A\"");
        assertThat(patched.draft().digest()).isNotEqualTo(initial.digest());
    }

    @Test
    void rejectsStaleDigestCrossAttemptAccessAndDanglingReferences() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        FencedAttempt owner = attempt("attempt-1", 1);
        var initial = store.create(owner, graph(
                "<mxCell id=\"node-a\" value=\"A\" vertex=\"1\" parent=\"1\"/>"
                        + "<mxCell id=\"edge-a\" edge=\"1\" parent=\"1\" source=\"node-a\" target=\"node-a\"/>"));

        assertThatThrownBy(() -> store.patch(
                owner,
                initial.ref(),
                "sha256:" + "0".repeat(64),
                List.of(DraftCellMutation.delete("edge-a"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DRAFT_DIGEST_MISMATCH");

        assertThatThrownBy(() -> store.read(attempt("attempt-2", 1), initial.ref()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DRAFT_ATTEMPT_NOT_FOUND");

        assertThatThrownBy(() -> store.patch(
                owner,
                initial.ref(),
                initial.digest(),
                List.of(DraftCellMutation.delete("node-a"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DRAFT_REFERENCE_MISSING");
    }

    @Test
    void rejectsUnsafeOrWholeDocumentReplacementInputs() {
        InMemoryDiagramDraftStore store = new InMemoryDiagramDraftStore();
        FencedAttempt attempt = attempt("attempt-1", 1);

        assertThatThrownBy(() -> store.create(
                attempt,
                "<!DOCTYPE foo><mxGraphModel><root/></mxGraphModel>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DRAFT_XML_BOUNDS_INVALID");
    }

    private String graph(String cells) {
        return "<mxGraphModel><root><mxCell id=\"0\"/>"
                + "<mxCell id=\"1\" parent=\"0\"/>" + cells + "</root></mxGraphModel>";
    }

    private FencedAttempt attempt(String attemptId, long epoch) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease(attemptId, epoch, now.plusSeconds(60), 60_000),
                1,
                "input-binding",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
    }
}
