package org.zipp.ai.test.trigger.http;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.admin.service.IAdminAuditLogStore;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopy;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopyStatus;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalCaseWorkingCopyService;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalCaseWorkingCopyStore;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;
import org.zipp.ai.trigger.http.EvaluationAdminController;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class EvaluationAdminControllerTest {

    @Test
    public void adminCanCreateListAndUpdateAWorkingCopy() {
        Fixture fixture = new Fixture(true);
        EvaluationAdminController.CreateRequest create = new EvaluationAdminController.CreateRequest();
        create.setSourceType("MANUAL");
        create.setDefinition(definition("case-1", "1", "hello"));

        Response<EvalCaseWorkingCopy> created = fixture.controller.create(create, request());
        Response<List<EvalCaseWorkingCopy>> listed = fixture.controller.list("DRAFT", null, 50, 0, request());
        EvaluationAdminController.UpdateRequest update = new EvaluationAdminController.UpdateRequest();
        update.setExpectedRevision(1L);
        update.setDefinition(definition("case-1", "1", "updated"));
        Response<EvalCaseWorkingCopy> updated = fixture.controller.update(created.getData().getId(), update, request());

        assertEquals("0000", created.getCode());
        assertEquals(1, listed.getData().size());
        assertEquals(Long.valueOf(2L), updated.getData().getRevision());
        assertEquals("updated", updated.getData().getDefinition().getInput().get("user"));
        assertFalse(fixture.auditLogs.logs.isEmpty());
    }

    @Test
    public void staleRevisionReturnsAStableConflictCodeAndIsAudited() {
        Fixture fixture = new Fixture(true);
        EvaluationAdminController.CreateRequest create = new EvaluationAdminController.CreateRequest();
        create.setSourceType("MANUAL"); create.setDefinition(definition("case-2", "1", "hello"));
        EvalCaseWorkingCopy workingCopy = fixture.controller.create(create, request()).getData();
        EvaluationAdminController.UpdateRequest update = new EvaluationAdminController.UpdateRequest();
        update.setExpectedRevision(0L); update.setDefinition(definition("case-2", "1", "updated"));

        Response<EvalCaseWorkingCopy> response = fixture.controller.update(workingCopy.getId(), update, request());

        assertEquals("REVISION_CONFLICT", response.getCode());
        assertEquals("REJECTED", fixture.auditLogs.logs.get(fixture.auditLogs.logs.size() - 1).getOutcome());
    }

    @Test
    public void nonAdminCannotAccessWorkingCopies() {
        Fixture fixture = new Fixture(false);

        Response<List<EvalCaseWorkingCopy>> response = fixture.controller.list(null, null, 50, 0, request());

        assertEquals("AUTH_FORBIDDEN", response.getCode());
        assertEquals(0, fixture.auditLogs.logs.size());
    }

    private static EvalCaseDefinition definition(String id, String version, String user) {
        return EvalCaseDefinition.builder().caseId(id).caseVersion(version).datasetVersion("dev-draft")
                .input(Map.of("user", user))
                .privacy(new EvalCaseDefinition.Privacy("synthetic", "manual-v1")).build();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.20"); request.addHeader("User-Agent", "EvaluationAdminControllerTest");
        return request;
    }

    private static final class Fixture {
        private final AuditStore auditLogs = new AuditStore();
        private final EvaluationAdminController controller;

        private Fixture(boolean authenticated) {
            WorkingCopyStore store = new WorkingCopyStore();
            EvalCaseWorkingCopyService service = new EvalCaseWorkingCopyService(store, new EmptyTraceStore(),
                    Clock.fixed(Instant.parse("2026-07-13T02:00:00Z"), ZoneOffset.UTC));
            UserAccount admin = UserAccount.builder().id("admin-1").build();
            AdminAuthorizationService authorization = new AdminAuthorizationService() {
                @Override public Optional<UserAccount> currentAdmin(jakarta.servlet.http.HttpServletRequest request) {
                    return authenticated ? Optional.of(admin) : Optional.empty();
                }
            };
            controller = new EvaluationAdminController(service, authorization,
                    new AdminAuditLogService(auditLogs, Clock.fixed(Instant.parse("2026-07-13T02:00:00Z"), ZoneOffset.UTC)));
        }
    }

    private static final class WorkingCopyStore implements IEvalCaseWorkingCopyStore {
        private final Map<String, EvalCaseWorkingCopy> values = new LinkedHashMap<>();
        @Override public Optional<EvalCaseWorkingCopy> find(String id) { return Optional.ofNullable(values.get(id)); }
        @Override public List<EvalCaseWorkingCopy> list(EvalCaseWorkingCopyStatus status, String owner, int limit, int offset) {
            return values.values().stream().filter(v -> status == null || status == v.getStatus())
                    .filter(v -> owner == null || owner.equals(v.getOwnerUserId())).skip(offset).limit(limit).toList();
        }
        @Override public void insert(EvalCaseWorkingCopy value) { values.put(value.getId(), value); }
        @Override public boolean update(EvalCaseWorkingCopy value, long revision) {
            EvalCaseWorkingCopy current = values.get(value.getId());
            if (current == null || current.getRevision() != revision) return false;
            values.put(value.getId(), value); return true;
        }
    }

    private static final class AuditStore implements IAdminAuditLogStore {
        private final List<AdminAuditLog> logs = new ArrayList<>();
        @Override public void insert(AdminAuditLog log) { logs.add(log); }
        @Override public List<AdminAuditLog> listRecent(int limit) { return logs.stream().limit(limit).toList(); }
    }

    private static final class EmptyTraceStore implements ITraceToEvalStore {
        @Override public Optional<org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate> findCandidate(String candidateId) { return Optional.empty(); }
        @Override public Optional<org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String sourceRunId, String failureFamily) { return Optional.empty(); }
        @Override public void insertCandidate(org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate candidate) { }
        @Override public void updateCandidateStatus(String candidateId, org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus status) { }
        @Override public void insertReview(org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseReview review) { }
        @Override public void insertLineage(org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseLineage lineage) { }
    }
}
