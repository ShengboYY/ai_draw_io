package org.zipp.ai.domain.citation.answer;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.citation.model.valobj.SupportAtom;
import org.zipp.ai.domain.citation.model.valobj.SupportAtomRole;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.retrieval.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceAnswerServiceTest {

    @Test
    void commitsOnlyServerRenderedSupportedClaimsAndKeepsCanvasTupleAsExpectation() {
        AnswerClaim claim = new AnswerClaim("C1", "Teams inspect progress every day", List.of("E1"),
                AnswerSupportType.DIRECT,
                List.of(new SupportAtom("A1", "E1", "Teams inspect progress every day",
                        SupportAtomRole.DIRECT_QUOTE)));
        EvidenceAnswerGeneratorPort generator = command -> new AnswerProposal(List.of(claim), List.of(), List.of());
        AtomicReference<EvidenceAnswerCommitPort.CommitPlan> committed = new AtomicReference<>();
        EvidenceAnswerCommitPort commits = plan -> {
            committed.set(plan);
            return EvidenceAnswerCommitPort.CommitStatus.COMMITTED;
        };
        EvidenceAnswerService service = new EvidenceAnswerService(generator,
                new EvidenceAnswerGuard(requests -> List.of()), commits);
        RunResourceDomain resources = new RunResourceDomain();
        resources.markPrepared();
        PreparedEvidence prepared = new PreparedEvidence(new EvidenceBundle("bundle", "request", "run",
                SourceMode.AUTO, List.of(item())), resources);

        EvidenceAnswerResult result = service.answer(new EvidenceAnswerCommand(
                new CatalogOwner(OwnerType.USER, "alice"), "diagram-1", "session-1",
                "message-1", "request", "run", "What happens daily?", "selected node",
                "previous turn", 7L, "hash-7", false), prepared);

        assertTrue(result.committed());
        assertEquals("- Teams inspect progress every day [C1]", result.content());
        assertEquals(7L, committed.get().expectedCanvasVersion());
        assertEquals("hash-7", committed.get().expectedCanvasContentHash());
        assertEquals("message-1", committed.get().messageId());
        assertEquals(1, committed.get().citations().size());
        assertEquals(RunResourceState.CLOSED, resources.state());
        assertEquals(CloseReason.COMMITTED, resources.closeReason().orElseThrow());
    }

    private EvidenceBundleItem item() {
        return new EvidenceBundleItem("E1", "ev-1", "mat-1", "v1", "r1",
                "Guide", 4, "TEXT", "Teams inspect progress every day");
    }
}
