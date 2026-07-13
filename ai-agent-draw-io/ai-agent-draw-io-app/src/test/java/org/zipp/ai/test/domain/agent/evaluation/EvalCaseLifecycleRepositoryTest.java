package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane.EvalCaseEvidenceRepository;
import org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane.EvalCaseWorkingCopyReviewRepository;
import org.zipp.ai.infrastructure.dao.IEvalCaseLifecycleMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.EvalCaseEvidencePO;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.EvalCaseWorkingCopyReviewPO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class EvalCaseLifecycleRepositoryTest {
    @Test
    public void evidenceAndReviewAdaptersRoundTripImmutableRecords() {
        FakeMapper mapper = new FakeMapper();
        Instant now = Instant.parse("2026-07-13T04:00:00Z");
        EvalCaseEvidenceRepository evidence = new EvalCaseEvidenceRepository(mapper);
        EvalCaseWorkingCopyReviewRepository reviews = new EvalCaseWorkingCopyReviewRepository(mapper);

        evidence.insert(EvalCaseEvidence.builder().id("e1").workingCopyId("w1").workingCopyRevision(4L)
                .type(EvalCaseEvidenceType.DRY_RUN).status("PASS").payloadJson("{}").componentVersion("v1")
                .createdAt(now).build());
        reviews.insert(EvalCaseWorkingCopyReview.builder().id("r1").workingCopyId("w1").workingCopyRevision(5L)
                .reviewerUserId("reviewer").decision("APPROVE").reason("verified").createdAt(now).build());

        assertEquals(EvalCaseEvidenceType.DRY_RUN, evidence.list("w1").get(0).getType());
        assertEquals("reviewer", reviews.list("w1").get(0).getReviewerUserId());
    }

    private static final class FakeMapper implements IEvalCaseLifecycleMapper {
        private final List<EvalCaseEvidencePO> evidence = new ArrayList<>();
        private final List<EvalCaseWorkingCopyReviewPO> reviews = new ArrayList<>();
        @Override public int insertEvidence(EvalCaseEvidencePO value) { evidence.add(value); return 1; }
        @Override public List<EvalCaseEvidencePO> selectEvidence(String id) { return evidence; }
        @Override public int insertReview(EvalCaseWorkingCopyReviewPO value) { reviews.add(value); return 1; }
        @Override public List<EvalCaseWorkingCopyReviewPO> selectReviews(String id) { return reviews; }
    }
}
