package org.zipp.ai.test.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.admin.service.IAdminAuditLogStore;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.*;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;
import org.zipp.ai.trigger.http.EvaluationOperationsAdminController;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;

import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;

public class EvaluationOperationsAdminControllerTest {

    @Test
    public void canaryAssessmentRequiresReleaseOwnerAndAuditsBothOutcomes() {
        RunStore runs = new RunStore();
        runs.insertRun(EvalRun.builder().id("release-1").mode(EvalRunMode.RELEASE).status(EvalRunStatus.COMPLETED).build());
        runs.saveGate(EvalGateDecisionRecord.builder().evalRunId("release-1").outcome(EvalGateOutcome.PASS).build());
        CanaryStore canaries = new CanaryStore(); AuditStore auditStore = new AuditStore();
        ReleaseAuthorization authorization = new ReleaseAuthorization(false);
        EvaluationOperationsAdminController controller = new EvaluationOperationsAdminController(
                new EvalCanaryOperationsService(runs, canaries),
                new EvalCaseHealthOperationsService(runs, new EmptyCaseStore(), new EmptyHealthStore()),
                authorization, new AdminAuditLogService(auditStore));
        EvaluationOperationsAdminController.CanaryRequest request = request();

        Response<EvalCanaryAssessment> rejected = controller.assess(request, new MockHttpServletRequest());
        authorization.releaseOwner = true;
        Response<EvalCanaryAssessment> accepted = controller.assess(request, new MockHttpServletRequest());

        assertNull(rejected.getData());
        assertEquals("REJECTED", auditStore.logs.get(0).getOutcome());
        assertEquals("0000", accepted.getCode());
        assertEquals(org.zipp.ai.domain.agent.service.evaluation.EvalCanaryService.Outcome.CONTINUE, accepted.getData().getOutcome());
        assertEquals("SUCCESS", auditStore.logs.get(1).getOutcome());
        assertEquals(1, canaries.values.size());
    }

    private EvaluationOperationsAdminController.CanaryRequest request() {
        EvaluationOperationsAdminController.Window baseline = new EvaluationOperationsAdminController.Window();
        baseline.setRequests(1000); baseline.setFailures(10); baseline.setP95LatencyMs(100); baseline.setAverageCost(0.01);
        EvaluationOperationsAdminController.Window canary = new EvaluationOperationsAdminController.Window();
        canary.setRequests(100); canary.setFailures(1); canary.setP95LatencyMs(100); canary.setAverageCost(0.01);
        EvaluationOperationsAdminController.Policy policy = new EvaluationOperationsAdminController.Policy();
        EvaluationOperationsAdminController.CanaryRequest request = new EvaluationOperationsAdminController.CanaryRequest();
        request.setEvalRunId("release-1"); request.setDeploymentRef("deployment-1"); request.setPolicyVersion("policy-v1");
        request.setBaseline(baseline); request.setCanary(canary); request.setPolicy(policy); return request;
    }

    private static final class ReleaseAuthorization extends AdminAuthorizationService {
        private boolean releaseOwner;
        private ReleaseAuthorization(boolean releaseOwner) { this.releaseOwner = releaseOwner; }
        @Override public Optional<UserAccount> currentAdmin(HttpServletRequest request) { return Optional.of(UserAccount.builder().id("admin-1").build()); }
        @Override public boolean isReleaseOwner(UserAccount user) { return releaseOwner; }
    }
    private static final class CanaryStore implements IEvalCanaryAssessmentStore {
        private final List<EvalCanaryAssessment> values = new ArrayList<>();
        @Override public void insert(EvalCanaryAssessment value) { values.add(value); }
        @Override public List<EvalCanaryAssessment> list(String run, int limit) { return values; }
    }
    private static final class RunStore implements IEvalRunStore {
        private final Map<String, EvalRun> values = new HashMap<>(); private EvalGateDecisionRecord gate;
        @Override public void insertRun(EvalRun run) { values.put(run.getId(), run); }
        @Override public void updateRun(EvalRun run) { values.put(run.getId(), run); }
        @Override public Optional<EvalRun> findRun(String id) { return Optional.ofNullable(values.get(id)); }
        @Override public Optional<EvalRun> findByIdempotencyKey(String key) { return Optional.empty(); }
        @Override public List<EvalRun> listRuns(int limit, int offset) { return List.copyOf(values.values()); }
        @Override public void saveEpisode(EvalEpisode episode) { }
        @Override public Optional<EvalEpisode> findEpisode(String id) { return Optional.empty(); }
        @Override public List<EvalEpisode> listEpisodes(String run) { return List.of(); }
        @Override public void replaceGraders(String id, List<EvalGraderResultRecord> graders) { }
        @Override public List<EvalGraderResultRecord> listGraders(String id) { return List.of(); }
        @Override public void saveGate(EvalGateDecisionRecord gate) { this.gate = gate; }
        @Override public Optional<EvalGateDecisionRecord> findGate(String run) { return Optional.ofNullable(gate); }
    }
    private static final class EmptyCaseStore implements IEvalCaseVersionStore {
        @Override public void insert(EvalCaseVersion value) { }
        @Override public Optional<EvalCaseVersion> find(String id, String version) { return Optional.empty(); }
        @Override public List<EvalCaseVersion> list(String id) { return List.of(); }
        @Override public boolean retire(String id, String version, Instant at) { return false; }
    }
    private static final class EmptyHealthStore implements ITraceToEvalStore {
        @Override public Optional<EvalCaseCandidate> findCandidate(String id) { return Optional.empty(); }
        @Override public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String run, String family) { return Optional.empty(); }
        @Override public void insertCandidate(EvalCaseCandidate candidate) { }
        @Override public void updateCandidateStatus(String id, EvalCandidateStatus status) { }
        @Override public void insertReview(EvalCaseReview review) { }
        @Override public void insertLineage(EvalCaseLineage lineage) { }
    }
    private static final class AuditStore implements IAdminAuditLogStore {
        private final List<AdminAuditLog> logs = new ArrayList<>();
        @Override public void insert(AdminAuditLog log) { logs.add(log); }
        @Override public List<AdminAuditLog> listRecent(int limit) { return logs; }
    }
}
