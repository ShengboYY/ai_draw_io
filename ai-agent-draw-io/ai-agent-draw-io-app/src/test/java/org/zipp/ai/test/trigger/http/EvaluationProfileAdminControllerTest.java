package org.zipp.ai.test.trigger.http;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.admin.service.IAdminAuditLogStore;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvaluationProfileVersion;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.DefaultEvaluationProfiles;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvaluationProfileResolver;
import org.zipp.ai.trigger.http.EvaluationProfileAdminController;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;

import java.time.Clock;
import java.util.*;

import static org.junit.Assert.assertEquals;

public class EvaluationProfileAdminControllerTest {
    @Test
    public void profileCatalogRequiresAdminAndReturnsSixReadOnlyVersions() {
        Fixture denied = new Fixture(false);
        assertEquals("AUTH_FORBIDDEN", denied.controller.list(request()).getCode());

        Fixture allowed = new Fixture(true);
        Response<List<EvaluationProfileVersion>> response = allowed.controller.list(request());

        assertEquals("0000", response.getCode());
        assertEquals(6, response.getData().size());
        assertEquals("LIST_EVALUATION_PROFILES", allowed.audits.values.get(0).getAction());
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.41");
        return request;
    }

    private static final class Fixture {
        private final AuditStore audits = new AuditStore();
        private final EvaluationProfileAdminController controller;

        private Fixture(boolean authenticated) {
            UserAccount admin = UserAccount.builder().id("admin-1").build();
            AdminAuthorizationService authorization = new AdminAuthorizationService() {
                @Override public Optional<UserAccount> currentAdmin(jakarta.servlet.http.HttpServletRequest request) {
                    return authenticated ? Optional.of(admin) : Optional.empty();
                }
            };
            controller = new EvaluationProfileAdminController(
                    new EvaluationProfileResolver(DefaultEvaluationProfiles::versions), authorization,
                    new AdminAuditLogService(audits, Clock.systemUTC()));
        }
    }

    private static final class AuditStore implements IAdminAuditLogStore {
        private final List<AdminAuditLog> values = new ArrayList<>();
        @Override public void insert(AdminAuditLog value) { values.add(value); }
        @Override public List<AdminAuditLog> listRecent(int limit) { return values.stream().limit(limit).toList(); }
    }
}
