package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalDatasetVersion;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalDataset;

import java.util.List;
import java.util.Optional;

/** Metadata port for versioned evaluation datasets. */
public interface IEvalDatasetStore {
    void insertDataset(EvalDataset dataset);

    Optional<EvalDataset> findDataset(String datasetId);

    List<EvalDataset> listDatasets();

    void insert(EvalDatasetVersion datasetVersion);

    boolean update(EvalDatasetVersion datasetVersion, long expectedRevision);

    Optional<EvalDatasetVersion> find(String datasetId, String version);

    List<EvalDatasetVersion> listVersions(String datasetId);
}
