package org.zipp.ai.domain.grounding.port;

import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateSaveResult;
import org.zipp.ai.domain.citation.model.valobj.SupportType;

import java.util.List;
import java.util.Set;

/** Single transaction boundary for grounded canvas, provenance, citations, pins and run fencing. */
public interface GroundedCanvasCommitPort {
    /** Resolves inheritance from the authoritative previous provenance projection. */
    java.util.Map<String, InheritedProvenance> findPersistedProvenance(InheritanceQuery query);

    CanvasStateSaveResult commit(CommitPlan plan);

    record InheritanceQuery(String ownerKey, String diagramId, Long canvasVersion,
                            Set<String> candidateCellIds) {
        public InheritanceQuery {
            candidateCellIds = Set.copyOf(candidateCellIds == null ? Set.of() : candidateCellIds);
        }
    }

    record InheritedProvenance(String provenanceRef, SupportType supportType) { }

    record CommitPlan(String ownerKey, String diagramId, Long expectedVersion, String expectedContentHash,
                      String requestId, String runId, String canvasXml, String contentHash,
                      String mutationOrigin, List<CitationWrite> citations,
                      Set<String> inheritedCellIds, long expectedRunGeneration) {
        public CommitPlan {
            citations = List.copyOf(citations == null ? List.of() : citations);
            inheritedCellIds = Set.copyOf(inheritedCellIds == null ? Set.of() : inheritedCellIds);
            if (expectedRunGeneration < 1) throw new IllegalArgumentException("expectedRunGeneration must be positive");
        }
    }

    record CitationWrite(String citationId, String provenanceRef, String cellId, String statementKey,
                         SupportType supportType, String semanticHash, List<EvidenceLink> evidenceLinks) {
        public CitationWrite { evidenceLinks = List.copyOf(evidenceLinks == null ? List.of() : evidenceLinks); }
    }

    record EvidenceLink(String citationKey, String evidenceId, String materialId,
                        String versionId, String revisionId, String useRole) { }
}
