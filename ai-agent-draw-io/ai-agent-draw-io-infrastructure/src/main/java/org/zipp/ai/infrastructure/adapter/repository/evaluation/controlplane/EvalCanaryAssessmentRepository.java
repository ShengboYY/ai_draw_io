package org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane;

import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCanaryAssessment;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalCanaryAssessmentStore;
import org.zipp.ai.infrastructure.dao.IEvalCanaryAssessmentMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.EvalCanaryAssessmentPO;

import java.util.List;

@Repository
public class EvalCanaryAssessmentRepository implements IEvalCanaryAssessmentStore {
    private final IEvalCanaryAssessmentMapper mapper;

    public EvalCanaryAssessmentRepository(IEvalCanaryAssessmentMapper mapper) { this.mapper = mapper; }

    @Override
    public void insert(EvalCanaryAssessment value) { mapper.insert(po(value)); }

    @Override
    public List<EvalCanaryAssessment> list(String runId, int limit) {
        return mapper.list(runId, limit).stream().map(this::domain).toList();
    }

    private EvalCanaryAssessmentPO po(EvalCanaryAssessment value) {
        EvalCanaryAssessmentPO po = new EvalCanaryAssessmentPO();
        po.setId(value.getId());
        po.setEvalRunId(value.getEvalRunId());
        po.setDeploymentRef(value.getDeploymentRef());
        po.setPolicyVersion(value.getPolicyVersion());
        po.setOutcome(value.getOutcome().name());
        po.setReasonsJson(JSON.toJSONString(value.getReasons()));
        po.setBaselineRequests(value.getBaselineRequests());
        po.setCanaryRequests(value.getCanaryRequests());
        po.setCanaryFailures(value.getCanaryFailures());
        po.setCriticalFindings(value.getCriticalFindings());
        po.setInfrastructureErrors(value.getInfrastructureErrors());
        po.setP95LatencyMs(value.getP95LatencyMs());
        po.setAverageCost(value.getAverageCost());
        po.setCreatedBy(value.getCreatedBy());
        po.setCreatedAt(java.util.Date.from(value.getCreatedAt()));
        return po;
    }

    private EvalCanaryAssessment domain(EvalCanaryAssessmentPO po) {
        return EvalCanaryAssessment.builder().id(po.getId()).evalRunId(po.getEvalRunId())
                .deploymentRef(po.getDeploymentRef()).policyVersion(po.getPolicyVersion())
                .outcome(org.zipp.ai.domain.agent.service.evaluation.EvalCanaryService.Outcome.valueOf(po.getOutcome()))
                .reasons(po.getReasonsJson() == null ? List.of() : JSON.parseArray(po.getReasonsJson(), String.class))
                .baselineRequests(number(po.getBaselineRequests())).canaryRequests(number(po.getCanaryRequests()))
                .canaryFailures(number(po.getCanaryFailures())).criticalFindings(number(po.getCriticalFindings()))
                .infrastructureErrors(number(po.getInfrastructureErrors())).p95LatencyMs(decimal(po.getP95LatencyMs()))
                .averageCost(decimal(po.getAverageCost())).createdBy(po.getCreatedBy())
                .createdAt(po.getCreatedAt() == null ? null : po.getCreatedAt().toInstant()).build();
    }

    private int number(Integer value) { return value == null ? 0 : value; }
    private double decimal(Double value) { return value == null ? 0D : value; }
}
