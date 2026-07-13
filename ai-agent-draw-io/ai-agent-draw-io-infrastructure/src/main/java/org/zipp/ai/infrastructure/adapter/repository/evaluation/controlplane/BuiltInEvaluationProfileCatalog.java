package org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane;

import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvaluationProfileVersion;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.DefaultEvaluationProfiles;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvaluationProfileCatalog;

import java.util.List;

/** Infrastructure adapter exposing source-controlled Profile presets as a read-only catalog. */
@Repository
public class BuiltInEvaluationProfileCatalog implements IEvaluationProfileCatalog {
    @Override public List<EvaluationProfileVersion> list() { return DefaultEvaluationProfiles.versions(); }
}
