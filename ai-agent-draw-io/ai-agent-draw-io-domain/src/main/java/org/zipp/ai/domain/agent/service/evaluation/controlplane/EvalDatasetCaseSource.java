package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;

import java.util.List;

/** Loads an immutable Dataset Version as executable Case definitions for CP4+ runners. */
@Service
public class EvalDatasetCaseSource implements IEvalDatasetCaseSource {
    private final EvalDatasetService datasets;
    private final EvalCasePublisherService cases;

    public EvalDatasetCaseSource(EvalDatasetService datasets, EvalCasePublisherService cases) {
        this.datasets = datasets; this.cases = cases;
    }

    @Override
    public List<EvalCaseDefinition> loadPublished(String datasetId, String version, EvalAdminRole role) {
        EvalDatasetVersion dataset = datasets.get(datasetId, version);
        if (dataset.getStatus() != EvalDatasetVersionStatus.PUBLISHED) {
            throw new IllegalStateException("Eval Run requires a published Dataset Version");
        }
        if (dataset.getDatasetClass() == EvalDatasetClass.SEQUESTERED && role != EvalAdminRole.RELEASE_OWNER) {
            throw new SecurityException("sequestered dataset contents require Release Owner role");
        }
        return dataset.getMembers().stream().map(member -> {
            // Runtime metadata is copied so the immutable artifact bytes and content hash never change.
            EvalCaseDefinition definition = JSON.parseObject(
                    JSON.toJSONString(cases.load(member.getCaseId(), member.getCaseVersion())), EvalCaseDefinition.class);
            definition.setDatasetVersion(version);
            return definition;
        }).toList();
    }
}
