package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.retrieval.RetrievalRoute;

import java.util.List;

public interface RetrievalLexicalIndex {
    List<CandidateRef> search(List<String> queries, AuthorizedSourceSet sources,
                              RetrievalRoute route, int limit);
}
