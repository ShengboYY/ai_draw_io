package org.zipp.ai.test.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.admin.service.IAdminAuditLogStore;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.*;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.evaluation.intake.ISemanticAnomalyMiner;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;
import org.zipp.ai.domain.agent.service.evaluation.intake.SemanticAnomalyDiscoveryService;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;
import org.zipp.ai.trigger.http.SemanticMinerAdminController;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class SemanticMinerAdminControllerTest {

    @Test
    public void adminStartIsAuditedAndFailsClosedUntilCalibrated() {
        MemoryStore store = new MemoryStore();
        ISemanticAnomalyMiner miner = new ISemanticAnomalyMiner() {
            @Override public Finding analyze(String projection) { throw new AssertionError("disabled miner must not run"); }
            @Override public String version() { return "fake-semantic-v1"; }
        };
        SemanticAnomalyDiscoveryService service = new SemanticAnomalyDiscoveryService(
                new FakeAgentUsageTelemetryStore(), store, new AgentDebugTraceService(null, null), miner,
                Runnable::run, false, false, "unconfigured", 20, 0D,
                Clock.fixed(Instant.parse("2026-07-13T08:00:00Z"), ZoneOffset.UTC));
        AuditStore auditStore = new AuditStore();
        AdminAuthorizationService authorization = new AdminAuthorizationService() {
            @Override public Optional<UserAccount> currentAdmin(HttpServletRequest request) {
                return Optional.of(UserAccount.builder().id("admin-1").build());
            }
        };
        SemanticMinerAdminController controller = new SemanticMinerAdminController(service, authorization,
                new AdminAuditLogService(auditStore));
        SemanticMinerAdminController.StartRequest body = new SemanticMinerAdminController.StartRequest();
        body.setSamplingPolicy("TARGETED"); body.setLimit(20); body.setPurposeConfirmed(true);

        Response<SemanticMinerRun> response = controller.start(body, new MockHttpServletRequest());

        assertEquals("0000", response.getCode());
        assertEquals(SemanticMinerRunStatus.UNAVAILABLE, response.getData().getStatus());
        assertEquals("semantic_miner_disabled", response.getData().getAvailabilityReason());
        assertEquals("START_SEMANTIC_MINER_RUN", auditStore.logs.get(0).getAction());
        assertEquals("UNAVAILABLE", auditStore.logs.get(0).getOutcome());
    }

    private static final class MemoryStore implements ITraceToEvalStore {
        private final List<SemanticMinerRun> runs = new ArrayList<>();
        @Override public Optional<EvalCaseCandidate> findCandidate(String id) { return Optional.empty(); }
        @Override public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String run, String family) { return Optional.empty(); }
        @Override public void insertCandidate(EvalCaseCandidate candidate) { }
        @Override public void updateCandidateStatus(String id, EvalCandidateStatus status) { }
        @Override public void insertReview(EvalCaseReview review) { }
        @Override public void insertLineage(EvalCaseLineage lineage) { }
        @Override public void insertSemanticMinerRun(SemanticMinerRun run) { runs.add(run); }
        @Override public void updateSemanticMinerRun(SemanticMinerRun run) { }
        @Override public List<SemanticMinerRun> listSemanticMinerRuns(int limit) { return runs.stream().limit(limit).toList(); }
    }

    private static final class AuditStore implements IAdminAuditLogStore {
        private final List<AdminAuditLog> logs = new ArrayList<>();
        @Override public void insert(AdminAuditLog log) { logs.add(log); }
        @Override public List<AdminAuditLog> listRecent(int limit) { return logs.stream().limit(limit).toList(); }
    }
}
