package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.retrieval.EvidencePreparationCommand;

import java.util.List;

public interface EvidenceCatalog {
    SourceResolution resolveSources(EvidencePreparationCommand command);

    /** Maps opaque vector ids back to owner-authorized chunk ids before fusion. */
    List<CandidateRef> resolveVectorCandidates(List<String> vectorIds, AuthorizedSourceSet sources);

    /** Performs the final DB authorization/revision/status check before leases and blob reads. */
    List<AuthorizedCandidate> reauthorize(List<String> chunkIds, AuthorizedSourceSet sources, int limit);
}
