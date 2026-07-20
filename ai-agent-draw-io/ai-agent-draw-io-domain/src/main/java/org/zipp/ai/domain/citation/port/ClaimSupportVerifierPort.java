package org.zipp.ai.domain.citation.port;

import org.zipp.ai.domain.citation.model.valobj.ClaimSupportVerdict;

import java.util.List;

/** External no-tool entailment boundary; adapters must fail closed on timeout or invalid schema. */
public interface ClaimSupportVerifierPort {
    List<Result> verify(List<Request> requests);

    record Request(String runId, String statementKey, String statementText, List<String> anchors) {
        public Request { anchors = List.copyOf(anchors == null ? List.of() : anchors); }
    }

    record Result(String statementKey, ClaimSupportVerdict verdict) { }
}
