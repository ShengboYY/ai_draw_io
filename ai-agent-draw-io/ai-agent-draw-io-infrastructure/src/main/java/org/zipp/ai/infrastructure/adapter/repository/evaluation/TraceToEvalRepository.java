package org.zipp.ai.infrastructure.adapter.repository.evaluation;
import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.*;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;
import org.zipp.ai.infrastructure.dao.ITraceToEvalMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.*;
import javax.annotation.Resource;
import java.util.Optional;
@Repository
public class TraceToEvalRepository implements ITraceToEvalStore {
 @Resource private ITraceToEvalMapper mapper;
 public Optional<EvalCaseCandidate> findCandidate(String id){return Optional.ofNullable(mapper.selectCandidate(id)).map(this::candidate);}
 public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String r,String f){return Optional.ofNullable(mapper.selectCandidateBySource(r,f)).map(this::candidate);}
 public void insertCandidate(EvalCaseCandidate v){mapper.insertCandidate(candidatePo(v));} public void updateCandidateStatus(String id,EvalCandidateStatus s){mapper.updateCandidateStatus(id,s.name());}
 public void insertReview(EvalCaseReview v){EvalCaseReviewPO p=new EvalCaseReviewPO();p.setId(v.getId());p.setCandidateId(v.getCandidateId());p.setReviewer(v.getReviewer());p.setDecision(v.getDecision());p.setReason(v.getReason());p.setReviewedAt(java.util.Date.from(v.getReviewedAt()));mapper.insertReview(p);}
 public void insertLineage(EvalCaseLineage v){EvalCaseLineagePO p=new EvalCaseLineagePO();p.setPromotionId(v.getPromotionId());p.setCaseId(v.getCaseId());p.setDatasetVersion(v.getDatasetVersion());p.setReviewer(v.getReviewer());p.setApprovedAt(java.util.Date.from(v.getApprovedAt()));p.setSanitizerVersion(v.getSanitizerVersion());p.setOrigin(v.getOrigin());mapper.insertLineage(p);}
 private EvalCaseCandidate candidate(EvalCaseCandidatePO p){return EvalCaseCandidate.builder().id(p.getId()).sourceRunId(p.getSourceRunId()).sourceSpanId(p.getSourceSpanId()).sourcePhase(p.getSourcePhase()).sourceAgentId(p.getSourceAgentId()).failureFamily(p.getFailureFamily()).ruleId(p.getRuleId()).evidenceSummary(p.getEvidenceSummary()).risk(p.getRisk()).discoveredAt(p.getDiscoveredAt().toInstant()).policyVersion(p.getPolicyVersion()).status(EvalCandidateStatus.valueOf(p.getStatus())).createdBy(p.getCreatedBy()).build();}
 private EvalCaseCandidatePO candidatePo(EvalCaseCandidate v){EvalCaseCandidatePO p=new EvalCaseCandidatePO();p.setId(v.getId());p.setSourceRunId(v.getSourceRunId());p.setSourceSpanId(v.getSourceSpanId());p.setSourcePhase(v.getSourcePhase());p.setSourceAgentId(v.getSourceAgentId());p.setFailureFamily(v.getFailureFamily());p.setRuleId(v.getRuleId());p.setEvidenceSummary(v.getEvidenceSummary());p.setRisk(v.getRisk());p.setDiscoveredAt(java.util.Date.from(v.getDiscoveredAt()));p.setPolicyVersion(v.getPolicyVersion());p.setStatus(v.getStatus().name());p.setCreatedBy(v.getCreatedBy());return p;}
}
