package org.zipp.ai.test.trigger.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.zipp.ai.api.dto.AdminDebugTraceControlDTO;
import org.zipp.ai.api.dto.AdminDebugTraceControlRequestDTO;
import org.zipp.ai.api.dto.AdminDebugTraceCaptureDTO;
import org.zipp.ai.api.dto.AdminLlmCallDTO;
import org.zipp.ai.api.dto.AdminRunDetailDTO;
import org.zipp.ai.api.dto.AdminUsageDashboardDTO;
import org.zipp.ai.api.dto.AdminUserDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.EmailVerificationResult;
import org.zipp.ai.domain.account.model.valobj.LoginAccountCommand;
import org.zipp.ai.domain.account.model.valobj.LoginResult;
import org.zipp.ai.domain.account.model.valobj.PasswordResetResult;
import org.zipp.ai.domain.account.model.valobj.RegisterAccountCommand;
import org.zipp.ai.domain.account.model.valobj.RegistrationResult;
import org.zipp.ai.domain.account.service.IAccountService;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.admin.service.IAdminAuditLogStore;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceControl;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.debugtrace.IAgentDebugTraceStore;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;
import org.zipp.ai.trigger.http.AdminController;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.trigger.http.service.AuthenticatedSessionUser;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdminControllerTest {

    private FakeAccountService accounts;
    private FakeAgentUsageTelemetryStore telemetryStore;
    private FakeAdminAuditLogStore auditLogs;
    private FakeDebugTraceStore debugTraceStore;
    private AdminController controller;

    @Before
    public void setUp() throws Exception {
        accounts = new FakeAccountService();
        telemetryStore = new FakeAgentUsageTelemetryStore();
        auditLogs = new FakeAdminAuditLogStore();
        debugTraceStore = new FakeDebugTraceStore();
        AdminAuditLogService auditService = new AdminAuditLogService(
                auditLogs, Clock.fixed(Instant.parse("2026-07-03T10:00:00Z"), ZoneOffset.UTC));
        AdminAuthorizationService authorizationService = new AdminAuthorizationService();
        inject(authorizationService, "accountService", accounts);
        inject(authorizationService, "adminEmails", "admin@example.com");

        controller = new AdminController();
        inject(controller, "accountService", accounts);
        inject(controller, "agentUsageTelemetryService", new AgentUsageTelemetryService(telemetryStore));
        inject(controller, "adminAuditLogService", auditService);
        inject(controller, "agentDebugTraceService", new AgentDebugTraceService(
                debugTraceStore, auditService, Clock.fixed(Instant.parse("2026-07-03T10:00:00Z"), ZoneOffset.UTC)));
        inject(controller, "adminAuthorizationService", authorizationService);

        accounts.put(activeUser("usr_admin", "admin@example.com"));
        accounts.put(activeUser("usr_user", "user@example.com"));
    }

    @After
    public void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void nonAdminCannotAccessAdminEndpoints() {
        authenticate("usr_user", 0);

        Response<List<AdminUserDTO>> response = controller.listUsers(request());

        assertEquals("AUTH_FORBIDDEN", response.getCode());
        assertTrue(auditLogs.logs.isEmpty());
    }

    @Test
    public void adminRequestFromUntrustedOriginIsRejected() {
        authenticate("usr_admin", 0);
        MockHttpServletRequest request = request();
        request.addHeader("Origin", "https://evil.example");

        Response<List<AdminUserDTO>> response = controller.listUsers(request);

        assertEquals("AUTH_FORBIDDEN", response.getCode());
        assertTrue(auditLogs.logs.isEmpty());
    }

    @Test
    public void adminCanDisableUserAndWritesAuditLog() {
        authenticate("usr_admin", 0);

        Response<AdminUserDTO> response = controller.disableUser("usr_user", request());

        assertEquals("0000", response.getCode());
        assertEquals(AccountStatus.DISABLED.name(), response.getData().getStatus());
        UserAccount disabled = accounts.findById("usr_user").orElseThrow();
        assertEquals(AccountStatus.DISABLED, disabled.getStatus());
        assertEquals(1, disabled.getSessionVersion());
        assertEquals(1, auditLogs.logs.size());
        assertEquals("DISABLE_USER", auditLogs.logs.get(0).getAction());
        assertEquals("usr_user", auditLogs.logs.get(0).getTargetId());
    }

    @Test
    public void nonAdminCannotEnableDebugTrace() {
        authenticate("usr_user", 0);
        AdminDebugTraceControlRequestDTO dto = new AdminDebugTraceControlRequestDTO();
        dto.setUserId("usr_user");

        Response<AdminDebugTraceControlDTO> response = controller.enableDebugTrace(dto, request());

        assertEquals("AUTH_FORBIDDEN", response.getCode());
        assertTrue(debugTraceStore.controls.isEmpty());
        assertTrue(auditLogs.logs.isEmpty());
    }

    @Test
    public void adminCanEnableUserScopedDebugTraceAndWritesAuditLog() {
        authenticate("usr_admin", 0);
        AdminDebugTraceControlRequestDTO dto = new AdminDebugTraceControlRequestDTO();
        dto.setUserId("usr_user");

        Response<AdminDebugTraceControlDTO> response = controller.enableDebugTrace(dto, request());

        assertEquals("0000", response.getCode());
        assertEquals("usr_user", response.getData().getScopeUserId());
        assertEquals(1, debugTraceStore.controls.size());
        assertEquals(1, auditLogs.logs.size());
        assertEquals("ENABLE_DEBUG_TRACE", auditLogs.logs.get(0).getAction());
        assertEquals(response.getData().getId(), auditLogs.logs.get(0).getTargetId());
    }

    @Test
    public void adminCanViewDebugTraceCapturesAndWritesViewAudit() {
        authenticate("usr_admin", 0);
        debugTraceStore.captures.add(DebugTraceCapture.builder()
                .id("adt_1")
                .controlId("dtc_1")
                .userId("usr_user")
                .runId("aru_1")
                .eventType("CHAT_REQUEST")
                .content("debug prompt")
                .contentSha256("sha")
                .contentExpiresAt(Instant.parse("2026-07-10T10:00:00Z"))
                .createdAt(Instant.parse("2026-07-03T10:00:00Z"))
                .build());

        Response<List<AdminDebugTraceCaptureDTO>> response = controller.viewDebugTraceCaptures("aru_1", request());

        assertEquals("0000", response.getCode());
        assertEquals("debug prompt", response.getData().get(0).getContent());
        assertEquals(1, auditLogs.logs.size());
        assertEquals("VIEW_DEBUG_TRACE_CAPTURE", auditLogs.logs.get(0).getAction());
        assertEquals("aru_1", auditLogs.logs.get(0).getTargetId());
    }

    @Test
    public void adminUsageAndRunDetailExposeOnlyMetadata() {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder()
                .id("aru_1")
                .userId("usr_user")
                .agentId("drawio")
                .requestType("chat")
                .credentialSource("USER_KEY")
                .modelCredentialId("mcr_1")
                .status("SUCCESS")
                .latencyMs(120L)
                .startedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .build());
        telemetryStore.llmCalls.add(LlmCallTelemetry.builder()
                .id("alc_1")
                .runId("aru_1")
                .userId("usr_user")
                .phase("drawing")
                .provider("openai")
                .model("gpt-test")
                .credentialSource("USER_KEY")
                .modelCredentialId("mcr_1")
                .promptTokens(10)
                .completionTokens(15)
                .totalTokens(25)
                .status("SUCCESS")
                .latencyMs(80L)
                .startedAt(Instant.parse("2026-07-03T09:00:01Z"))
                .build());

        Response<AdminUsageDashboardDTO> usage = controller.usage(request());
        Response<AdminRunDetailDTO> detail = controller.runDetail("aru_1", request());

        assertEquals("0000", usage.getCode());
        assertEquals(Long.valueOf(1), usage.getData().getRequestCount());
        assertEquals("openai", usage.getData().getGroups().get(0).getProvider());
        assertEquals("0000", detail.getCode());
        assertEquals("mcr_1", detail.getData().getRun().getModelCredentialId());
        assertNoRawKeyFields(AdminLlmCallDTO.class);
        assertFalse(detail.getData().toString().contains("sk-live-secret"));
    }

    @Test
    public void runDetailIncludesUnifiedMetadataTimeline() {
        authenticate("usr_admin", 0);
        telemetryStore.runs.add(AgentRunTelemetry.builder()
                .id("aru_timeline")
                .requestId("req-timeline")
                .userId("usr_user")
                .agentId("drawio")
                .requestType("chat_stream")
                .credentialSource("PLATFORM")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .build());
        telemetryStore.traceEvents.add(AgentTraceEvent.builder()
                .id("ate_1")
                .runId("aru_timeline")
                .requestId("req-timeline")
                .userId("usr_user")
                .sequenceNo(1L)
                .eventType("HTTP_REQUEST_RECEIVED")
                .phase("request")
                .status("SUCCESS")
                .occurredAt(Instant.parse("2026-07-03T09:00:00Z"))
                .build());
        telemetryStore.steps.add(AgentRunStepTelemetry.builder()
                .id("ars_1")
                .runId("aru_timeline")
                .userId("usr_user")
                .phase("routing")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:01Z"))
                .completedAt(Instant.parse("2026-07-03T09:00:02Z"))
                .latencyMs(1000L)
                .build());
        telemetryStore.llmCalls.add(LlmCallTelemetry.builder()
                .id("alc_1")
                .runId("aru_timeline")
                .userId("usr_user")
                .phase("routing")
                .provider("openai")
                .model("gpt-test")
                .credentialSource("PLATFORM")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:01Z"))
                .latencyMs(800L)
                .build());
        telemetryStore.toolCalls.add(ToolCallTelemetry.builder()
                .id("atc_1")
                .runId("aru_timeline")
                .userId("usr_user")
                .phase("drawing")
                .toolName("create_diagram")
                .status("SUCCESS")
                .startedAt(Instant.parse("2026-07-03T09:00:03Z"))
                .build());

        Response<AdminRunDetailDTO> detail = controller.runDetail("aru_timeline", request());

        assertEquals("0000", detail.getCode());
        assertEquals("req-timeline", detail.getData().getRun().getRequestId());
        assertEquals(4, detail.getData().getTimeline().size());
        assertEquals("trace_event", detail.getData().getTimeline().get(0).getSource());
        assertEquals("HTTP_REQUEST_RECEIVED", detail.getData().getTimeline().get(0).getEventType());
        assertEquals("llm_call", detail.getData().getTimeline().get(2).getSource());
        assertEquals("tool_call", detail.getData().getTimeline().get(3).getSource());
    }

    private void assertNoRawKeyFields(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertFalse("admin DTO must not expose raw API keys", name.contains("apikey"));
            assertFalse("admin DTO must not expose secrets", name.contains("secret"));
            assertFalse("admin DTO must not expose encrypted key material", name.contains("encrypted"));
        }
    }

    private UserAccount activeUser(String id, String email) {
        return UserAccount.builder()
                .id(id)
                .email(email)
                .emailNormalized(email)
                .status(AccountStatus.ACTIVE)
                .sessionVersion(0)
                .createdAt(Instant.parse("2026-07-03T09:00:00Z"))
                .updatedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .verifiedAt(Instant.parse("2026-07-03T09:00:00Z"))
                .build();
    }

    private void authenticate(String userId, int sessionVersion) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedSessionUser(userId, sessionVersion),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.20");
        request.addHeader("User-Agent", "AdminControllerTest");
        return request;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class FakeAccountService implements IAccountService {
        private final ConcurrentHashMap<String, UserAccount> byId = new ConcurrentHashMap<>();

        void put(UserAccount user) {
            byId.put(user.getId(), user);
        }

        @Override public RegistrationResult register(RegisterAccountCommand command) { throw new UnsupportedOperationException(); }
        @Override public EmailVerificationResult verifyEmail(String rawToken) { throw new UnsupportedOperationException(); }
        @Override public void resendVerification(String email) { throw new UnsupportedOperationException(); }
        @Override public void requestPasswordReset(String email) { throw new UnsupportedOperationException(); }
        @Override public PasswordResetResult resetPassword(String rawToken, String rawPassword) { throw new UnsupportedOperationException(); }
        @Override public LoginResult login(LoginAccountCommand command) { throw new UnsupportedOperationException(); }

        @Override
        public Optional<UserAccount> findById(String userId) {
            return Optional.ofNullable(byId.get(userId));
        }

        @Override
        public List<UserAccount> listUsers() {
            return byId.values().stream()
                    .sorted(Comparator.comparing(UserAccount::getId))
                    .toList();
        }

        @Override
        public Optional<UserAccount> disableUser(String userId) {
            UserAccount user = byId.get(userId);
            if (user == null || user.getStatus() == AccountStatus.DISABLED) {
                return Optional.empty();
            }
            user.setStatus(AccountStatus.DISABLED);
            user.setSessionVersion(user.getSessionVersion() + 1);
            return Optional.of(user);
        }
    }

    private static final class FakeAdminAuditLogStore implements IAdminAuditLogStore {
        private final List<AdminAuditLog> logs = new ArrayList<>();

        @Override
        public void insert(AdminAuditLog log) {
            logs.add(log);
        }

        @Override
        public List<AdminAuditLog> listRecent(int limit) {
            return logs.stream().limit(limit).toList();
        }
    }

    private static final class FakeDebugTraceStore implements IAgentDebugTraceStore {
        private final List<DebugTraceControl> controls = new ArrayList<>();
        private final List<DebugTraceCapture> captures = new ArrayList<>();

        @Override
        public void insertControl(DebugTraceControl control) {
            controls.add(control);
        }

        @Override
        public List<DebugTraceControl> listEnabledControls() {
            return controls;
        }

        @Override
        public void insertCapture(DebugTraceCapture capture) {
        }

        @Override
        public int deleteExpiredContent(Instant now) {
            return 0;
        }

        @Override
        public int extendRunContentExpiry(String runId, Instant expiresAt) {
            return 0;
        }

        @Override
        public List<DebugTraceCapture> listCapturesByRunId(String runId) {
            return captures.stream()
                    .filter(capture -> runId.equals(capture.getRunId()))
                    .toList();
        }
    }
}
