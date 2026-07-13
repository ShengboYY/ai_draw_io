package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;

import java.util.List;
import java.util.Optional;

/** Mutable execution-state store; run manifests remain unchanged after insertion. */
public interface IEvalRunStore {
    void insertRun(EvalRun run);
    void updateRun(EvalRun run);
    Optional<EvalRun> findRun(String runId);
    Optional<EvalRun> findByIdempotencyKey(String idempotencyKey);
    List<EvalRun> listRuns(int limit, int offset);
    void saveEpisode(EvalEpisode episode);
    Optional<EvalEpisode> findEpisode(String episodeId);
    List<EvalEpisode> listEpisodes(String runId);
    void replaceGraders(String episodeId, List<EvalGraderResultRecord> graders);
    List<EvalGraderResultRecord> listGraders(String episodeId);
}
