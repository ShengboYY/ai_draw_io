package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.citation.answer.EvidenceAnswerCommitPort;
import org.zipp.ai.infrastructure.dao.grounding.AnswerCanvasTupleRowPO;
import org.zipp.ai.infrastructure.dao.grounding.GroundedRunRowPO;
import org.zipp.ai.infrastructure.dao.grounding.IEvidenceAnswerCommitMapper;

import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** MySQL linearization point for one grounded answer and all of its claim citations. */
@Repository
public class MySqlEvidenceAnswerCommitAdapter implements EvidenceAnswerCommitPort {
    private final IEvidenceAnswerCommitMapper mapper;

    public MySqlEvidenceAnswerCommitAdapter(IEvidenceAnswerCommitMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public CommitStatus commit(CommitPlan plan) {
        GroundedRunRowPO run = mapper.lockRunState(plan);
        if (run == null || !"RUNNING".equals(run.getState())
                || !Objects.equals(plan.expectedRunGeneration(), run.getGeneration())) {
            throw new IllegalStateException("GROUNDED_RUN_NOT_RUNNING");
        }
        AnswerCanvasTupleRowPO canvas = mapper.lockCanvasTuple(plan);
        if (canvas == null || canvas.getVersion() == null) {
            throw new IllegalStateException("ANSWER_CANVAS_NOT_FOUND");
        }
        if (plan.expectedCanvasVersion() != null
                && !Objects.equals(plan.expectedCanvasVersion(), canvas.getVersion())) {
            throw new IllegalStateException("ANSWER_CANVAS_VERSION_CHANGED");
        }
        if (plan.expectedCanvasContentHash() != null && !plan.expectedCanvasContentHash().isBlank()
                && !Objects.equals(plan.expectedCanvasContentHash(), canvas.getContentHash())) {
            throw new IllegalStateException("ANSWER_CANVAS_HASH_CHANGED");
        }
        // Lock evidence in one stable order before any write. This matches material lifecycle's
        // deterministic ordering and turns validation into the final race fence, not a snapshot.
        List<EvidenceLink> links = plan.citations().stream().flatMap(citation -> citation.evidenceLinks().stream())
                .distinct().sorted(Comparator.comparing(EvidenceLink::materialId)
                        .thenComparing(EvidenceLink::versionId)
                        .thenComparing(EvidenceLink::revisionId)
                        .thenComparing(EvidenceLink::evidenceId)).toList();
        for (EvidenceLink link : links) {
            if (!Objects.equals(link.evidenceId(), mapper.lockValidEvidenceLink(plan, link))) {
                throw new IllegalStateException("ANSWER_EVIDENCE_LEASE_INVALID");
            }
        }
        requireOne(mapper.insertMessage(plan), "ANSWER_MESSAGE_NOT_WRITTEN");
        Set<String> pinned = new HashSet<>();
        for (CitationWrite citation : plan.citations()) {
            requireOne(mapper.insertCitation(plan, citation, canvas.getVersion()), "ANSWER_CITATION_NOT_WRITTEN");
            for (EvidenceLink link : citation.evidenceLinks()) {
                requireOne(mapper.insertCitationEvidence(citation.citationId(), link),
                        "ANSWER_CITATION_EVIDENCE_NOT_WRITTEN");
                String pinKey = link.materialId() + "|" + link.versionId() + "|" + link.revisionId();
                if (pinned.add(pinKey)) requireOne(mapper.upsertSourcePin(plan, link), "ANSWER_PIN_NOT_WRITTEN");
            }
        }
        requireOne(mapper.completeRun(plan), "GROUNDED_RUN_NOT_COMPLETED");
        return CommitStatus.COMMITTED;
    }

    private void requireOne(int changed, String errorCode) {
        if (changed != 1) throw new IllegalStateException(errorCode);
    }
}
