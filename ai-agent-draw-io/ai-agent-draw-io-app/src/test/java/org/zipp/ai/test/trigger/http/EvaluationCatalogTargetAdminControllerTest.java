package org.zipp.ai.test.trigger.http;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.admin.service.IAdminAuditLogStore;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalAdminRole;
import org.zipp.ai.trigger.http.EvaluationCatalogAdminController;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class EvaluationCatalogTargetAdminControllerTest {

    @Test
    public void unauthenticatedCallerCannotListEvaluationTargets() {
        Fixture fixture = new Fixture(false);

        Response<List<EvaluationTarget>> response = fixture.controller.targets(request());

        assertEquals("AUTH_FORBIDDEN", response.getCode());
        assertEquals(0, fixture.audits.values.size());
    }

    @Test
    public void administratorCanListVersionedEvaluationTargetsAndActionIsAudited() {
        Fixture fixture = new Fixture(true);

        Response<List<EvaluationTarget>> response = fixture.controller.targets(request());

        assertEquals("0000", response.getCode());
        assertEquals(List.of(EvaluationTarget.values()), response.getData());
        assertEquals("LIST_EVALUATION_TARGETS", fixture.audits.values.get(0).getAction());
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.40");
        return request;
    }

    private static final class Fixture {
        private final AuditStore audits = new AuditStore();
        private final EvaluationCatalogAdminController controller;

        private Fixture(boolean authenticated) {
            UserAccount admin = UserAccount.builder().id("admin-1").build();
            AdminAuthorizationService authorization = new AdminAuthorizationService() {
                @Override public Optional<UserAccount> currentAdmin(jakarta.servlet.http.HttpServletRequest request) {
                    return authenticated ? Optional.of(admin) : Optional.empty();
                }
                @Override public EvalAdminRole evaluationRole(UserAccount user) { return EvalAdminRole.ADMIN; }
            };
            controller = new EvaluationCatalogAdminController(null, null, null, authorization,
                    new AdminAuditLogService(audits, Clock.systemUTC()));
        }
    }

    private static final class AuditStore implements IAdminAuditLogStore {
        private final List<AdminAuditLog> values = new ArrayList<>();
        @Override public void insert(AdminAuditLog value) { values.add(value); }
        @Override public List<AdminAuditLog> listRecent(int limit) { return values.stream().limit(limit).toList(); }
    }
}
