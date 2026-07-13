package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalDatasetVersion;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalDataset;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;

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

    /** Atomically validates a Dataset Version and assigns its member-derived Dataset target. */
    boolean validateWithTarget(EvalDatasetVersion datasetVersion, long expectedRevision, EvaluationTarget target);
}
