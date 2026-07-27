package org.zipp.ai.domain.citation.answer;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import java.util.List;

/** One transaction for assistant message, claim citations, evidence links, pins and run fencing. */
public interface EvidenceAnswerCommitPort {
    CommitStatus commit(CommitPlan plan);

    enum CommitStatus { COMMITTED, ALREADY_COMMITTED }

    record CommitPlan(CatalogOwner owner, String diagramId, String sessionId, String messageId,
                      String requestId, String runId, Long expectedCanvasVersion,
                      String expectedCanvasContentHash, long expectedRunGeneration,
                      String content, List<CitationWrite> citations) {
        public CommitPlan { citations = List.copyOf(citations == null ? List.of() : citations); }
    }

    record CitationWrite(String citationId, String claimKey, AnswerSupportType supportType,
                         String semanticHash, List<EvidenceLink> evidenceLinks) {
        public CitationWrite { evidenceLinks = List.copyOf(evidenceLinks == null ? List.of() : evidenceLinks); }
    }

    record EvidenceLink(String citationKey, String evidenceId, String materialId,
                        String versionId, String revisionId, String useRole, String origin) { }
}
