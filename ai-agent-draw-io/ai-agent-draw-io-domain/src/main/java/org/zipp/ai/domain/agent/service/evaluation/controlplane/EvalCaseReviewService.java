package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

/** Enforces review eligibility and records an explicit human decision. */
@Service
public class EvalCaseReviewService {
    private final EvalCaseWorkingCopyService workingCopies;
    private final IEvalCaseWorkingCopyReviewStore reviewStore;
    private final Clock clock;

    @Autowired
    public EvalCaseReviewService(EvalCaseWorkingCopyService workingCopies,
                                 IEvalCaseWorkingCopyReviewStore reviewStore) {
        this(workingCopies, reviewStore, Clock.systemUTC());
    }

    public EvalCaseReviewService(EvalCaseWorkingCopyService workingCopies,
                                 IEvalCaseWorkingCopyReviewStore reviewStore, Clock clock) {
        this.workingCopies = workingCopies;
        this.reviewStore = reviewStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EvalCaseWorkingCopy submit(String id, String actor, EvalAdminRole role) {
        return workingCopies.transition(id, EvalCaseWorkingCopyStatus.UNDER_REVIEW, actor, role);
    }

    public EvalCaseWorkingCopy decide(String id, String requestedDecision, String reason,
                                      String actor, EvalAdminRole role) {
        String decision = normalizeDecision(requestedDecision);
        requireReviewer(role);
        EvalCaseWorkingCopy current = workingCopies.get(id, actor, role);
        if (current.getStatus() != EvalCaseWorkingCopyStatus.UNDER_REVIEW) {
            throw new IllegalStateException("working copy is not under review");
        }
        if (StringUtils.isBlank(reason)) throw new IllegalArgumentException("review reason is required");
        reviewStore.insert(EvalCaseWorkingCopyReview.builder().id("ecwr_" + UUID.randomUUID())
                .workingCopyId(id).workingCopyRevision(current.getRevision()).reviewerUserId(actor)
                .decision(decision).reason(reason.trim()).createdAt(clock.instant()).build());
        return workingCopies.transition(id,
                "APPROVE".equals(decision) ? EvalCaseWorkingCopyStatus.APPROVED : EvalCaseWorkingCopyStatus.REJECTED,
                actor, role);
    }

    private void requireReviewer(EvalAdminRole role) {
        if (role != EvalAdminRole.REVIEWER && role != EvalAdminRole.ADMIN && role != EvalAdminRole.RELEASE_OWNER) {
            throw new SecurityException("reviewer role is required");
        }
    }

    private String normalizeDecision(String value) {
        if (StringUtils.isBlank(value)) throw new IllegalArgumentException("review decision is required");
        String decision = value.trim().toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            throw new IllegalArgumentException("unsupported review decision");
        }
        return decision;
    }
}
