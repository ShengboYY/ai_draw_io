package org.zipp.ai.test.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.admin.service.IAdminAuditLogStore;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalTargetGateCompositionService;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalRunStore;
import org.zipp.ai.trigger.http.EvaluationReleaseGateAdminController;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;

public class EvaluationReleaseGateAdminControllerTest {

    @Test
    public void nonAdminCannotUseTheCiAdapter() {
        Fixture fixture = fixture(new Authorization(false, false), service(new RunStore()));

        Response<EvalTargetGateCompositionService.Decision> response = fixture.controller.compose(request(), http());

        assertEquals("AUTH_FORBIDDEN", response.getCode());
        assertTrue(fixture.audits.logs.isEmpty());
    }

    @Test
    public void releaseOwnerIsRequiredAndRejectionIsAudited() {
        Fixture fixture = fixture(new Authorization(true, false), service(new RunStore()));

        Response<EvalTargetGateCompositionService.Decision> response = fixture.controller.compose(request(), http());

        assertEquals("AUTH_FORBIDDEN", response.getCode());
        assertEquals("REJECTED", fixture.audits.logs.get(0).getOutcome());
    }

    @Test
    public void validCompositionReturnsStableCiCodeAndAuditsSuccess() {
        RunStore runs = new RunStore();
        runs.addPassingRouter();
        Fixture fixture = fixture(new Authorization(true, true), service(runs));
        MockHttpServletRequest http = http();
        http.addHeader("User-Agent", "x".repeat(1000));

        Response<EvalTargetGateCompositionService.Decision> response = fixture.controller.compose(request(), http);

        assertEquals("0000", response.getCode());
        assertEquals(EvalGateOutcome.PASS, response.getData().outcome());
        assertEquals(0, response.getData().exitCode());
        assertEquals("SUCCESS", fixture.audits.logs.get(0).getOutcome());
        assertEquals(256, fixture.audits.logs.get(0).getUserAgent().length());
    }

    @Test
    public void optionalTargetWarningIsVisibleAndIndependentlyAudited() {
        RunStore runs = new RunStore();
        runs.add("router", EvaluationTarget.INTENT_ROUTER, EvalGateOutcome.PASS);
        runs.add("drawing", EvaluationTarget.DRAWING_QUALITY, EvalGateOutcome.BLOCK);
        Fixture fixture = fixture(new Authorization(true, true), service(runs));
        EvaluationReleaseGateAdminController.ComposeRequest request = request();
        request.setRunIds(Map.of("INTENT_ROUTER", "router", "DRAWING_QUALITY", "drawing"));

        Response<EvalTargetGateCompositionService.Decision> response = fixture.controller.compose(request, http());

        assertEquals(EvalGateOutcome.PASS, response.getData().outcome());
        assertEquals(1, response.getData().warnings().size());
        assertEquals("EVAL_RELEASE_GATE_OPTIONAL_WARNING", fixture.audits.logs.get(1).getAction());
        assertEquals("DRAWING_QUALITY:BLOCK", fixture.audits.logs.get(1).getTargetId());
        assertEquals("WARNING", fixture.audits.logs.get(1).getOutcome());
    }

    @Test
    public void invalidTargetIsRejectedAndAudited() {
        Fixture fixture = fixture(new Authorization(true, true), service(new RunStore()));
        EvaluationReleaseGateAdminController.ComposeRequest request = new EvaluationReleaseGateAdminController.ComposeRequest();
        request.setRunIds(Map.of("UNKNOWN_TARGET", "run-1"));
        request.setRequiredTargets(Set.of("UNKNOWN_TARGET"));

        Response<EvalTargetGateCompositionService.Decision> response = fixture.controller.compose(request, http());

        assertEquals("VALIDATION_FAILED", response.getCode());
        assertEquals("REJECTED", fixture.audits.logs.get(0).getOutcome());
    }

    @Test
    public void domainFailureIsGenericAndAuditedWithoutLeakingDetails() {
        EvalTargetGateCompositionService failing = new EvalTargetGateCompositionService(new RunStore()) {
            @Override public Decision compose(Map<EvaluationTarget, String> runs, Set<EvaluationTarget> required) {
                throw new RuntimeException("database password should not leak");
            }
        };
        Fixture fixture = fixture(new Authorization(true, true), failing);

        Response<EvalTargetGateCompositionService.Decision> response = fixture.controller.compose(request(), http());

        assertEquals("INFRASTRUCTURE_ERROR", response.getCode());
        assertEquals("Release Gate composition failed", response.getInfo());
        assertFalse(response.getInfo().contains("password"));
        assertEquals("ERROR", fixture.audits.logs.get(0).getOutcome());
    }

    private EvaluationReleaseGateAdminController.ComposeRequest request() {
        EvaluationReleaseGateAdminController.ComposeRequest request = new EvaluationReleaseGateAdminController.ComposeRequest();
        request.setRunIds(Map.of("INTENT_ROUTER", "router"));
        request.setRequiredTargets(Set.of("INTENT_ROUTER"));
        return request;
    }

    private Fixture fixture(Authorization authorization, EvalTargetGateCompositionService composition) {
        AuditStore audits = new AuditStore();
        return new Fixture(new EvaluationReleaseGateAdminController(composition, authorization,
                new AdminAuditLogService(audits)), audits);
    }

    private EvalTargetGateCompositionService service(RunStore runs) {
        return new EvalTargetGateCompositionService(runs);
    }

    private MockHttpServletRequest http() {
        return new MockHttpServletRequest();
    }

    private record Fixture(EvaluationReleaseGateAdminController controller, AuditStore audits) { }

    private static final class Authorization extends AdminAuthorizationService {
        private final boolean admin;
        private final boolean releaseOwner;
        private Authorization(boolean admin, boolean releaseOwner) {
            this.admin = admin; this.releaseOwner = releaseOwner;
        }
        @Override public Optional<UserAccount> currentAdmin(HttpServletRequest request) {
            return admin ? Optional.of(UserAccount.builder().id("admin-1").build()) : Optional.empty();
        }
        @Override public boolean isReleaseOwner(UserAccount user) { return releaseOwner; }
    }

    private static final class RunStore implements IEvalRunStore {
        private final Map<String, EvalRun> runs = new HashMap<>();
        private final Map<String, EvalGateDecisionRecord> gates = new HashMap<>();

        void addPassingRouter() {
            add("router", EvaluationTarget.INTENT_ROUTER, EvalGateOutcome.PASS);
        }

        void add(String id, EvaluationTarget target, EvalGateOutcome outcome) {
            runs.put(id, EvalRun.builder().id(id).mode(EvalRunMode.RELEASE).evaluationTarget(target).build());
            gates.put(id, EvalGateDecisionRecord.builder().evalRunId(id)
                    .outcome(outcome).reasonsJson("[\"fixture\"]").build());
        }

        public void insertRun(EvalRun run) { runs.put(run.getId(), run); }
        public void updateRun(EvalRun run) { runs.put(run.getId(), run); }
        public Optional<EvalRun> findRun(String id) { return Optional.ofNullable(runs.get(id)); }
        public Optional<EvalRun> findByIdempotencyKey(String key) { return Optional.empty(); }
        public List<EvalRun> listRuns(int limit, int offset) { return List.copyOf(runs.values()); }
        public void saveEpisode(EvalEpisode episode) { }
        public Optional<EvalEpisode> findEpisode(String id) { return Optional.empty(); }
        public List<EvalEpisode> listEpisodes(String run) { return List.of(); }
        public void replaceGraders(String id, List<EvalGraderResultRecord> graders) { }
        public List<EvalGraderResultRecord> listGraders(String id) { return List.of(); }
        public void saveGate(EvalGateDecisionRecord gate) { gates.put(gate.getEvalRunId(), gate); }
        public Optional<EvalGateDecisionRecord> findGate(String run) { return Optional.ofNullable(gates.get(run)); }
    }

    private static final class AuditStore implements IAdminAuditLogStore {
        private final List<AdminAuditLog> logs = new ArrayList<>();
        public void insert(AdminAuditLog log) { logs.add(log); }
        public List<AdminAuditLog> listRecent(int limit) { return logs; }
    }
}
