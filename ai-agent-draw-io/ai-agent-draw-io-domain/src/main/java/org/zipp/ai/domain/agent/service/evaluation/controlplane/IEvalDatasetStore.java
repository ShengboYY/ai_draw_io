package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalDatasetVersion;

import java.util.Optional;

/** Metadata port for versioned evaluation datasets. */
public interface IEvalDatasetStore {
    void insert(EvalDatasetVersion datasetVersion);

    Optional<EvalDatasetVersion> find(String datasetId, String version);
}
