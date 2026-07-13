package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCandidatePromotionLink;
import org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane.EvalCandidatePromotionLinkRepository;
import org.zipp.ai.infrastructure.dao.IEvalCandidatePromotionLinkMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.EvalCandidatePromotionLinkPO;

import java.time.Instant;

import static org.junit.Assert.assertEquals;

public class EvalCandidatePromotionLinkRepositoryTest {

    @Test
    public void insertIfAbsentReturnsTheCanonicalRestrictedLink() {
        FakeMapper mapper = new FakeMapper();
        EvalCandidatePromotionLinkRepository repository = new EvalCandidatePromotionLinkRepository(mapper);
        EvalCandidatePromotionLink first = link("working-1");
        EvalCandidatePromotionLink concurrent = link("working-2");

        EvalCandidatePromotionLink canonical = repository.insertIfAbsent(first);
        EvalCandidatePromotionLink repeated = repository.insertIfAbsent(concurrent);

        assertEquals("working-1", canonical.getWorkingCopyId());
        assertEquals(canonical.getWorkingCopyId(), repeated.getWorkingCopyId());
        assertEquals("candidate-1", repository.findByWorkingCopyId("working-1").orElseThrow().getCandidateId());
    }

    private EvalCandidatePromotionLink link(String workingCopyId) {
        return EvalCandidatePromotionLink.builder().candidateId("candidate-1").workingCopyId(workingCopyId)
                .caseId("case-1").caseVersion("1").promotedAt(Instant.parse("2026-07-14T01:00:00Z"))
                .retentionExpiresAt(Instant.parse("2026-10-12T01:00:00Z")).build();
    }

    private static final class FakeMapper implements IEvalCandidatePromotionLinkMapper {
        private EvalCandidatePromotionLinkPO value;
        @Override public EvalCandidatePromotionLinkPO selectByCandidateId(String candidateId) { return value; }
        @Override public EvalCandidatePromotionLinkPO selectByWorkingCopyId(String workingCopyId) {
            return value != null && workingCopyId.equals(value.getWorkingCopyId()) ? value : null;
        }
        @Override public int insertIfAbsent(EvalCandidatePromotionLinkPO link) {
            if (value != null) return 0;
            value = link;
            return 1;
        }
    }
}
