package org.zipp.ai.test.trigger.http;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.After;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.zipp.ai.api.dto.CurrentAccountResponseDTO;
import org.zipp.ai.api.dto.DiagramSummaryResponseDTO;
import org.zipp.ai.api.dto.UpdateDiagramTitleRequestDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.account.service.VerifiedUserPlatformQuotaService;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.trigger.http.AgentServiceController;
import org.zipp.ai.types.enums.ResponseCode;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentServiceControllerWorkspaceTest {

    private static final String VALID_WORKSPACE_ID = "anon_123e4567-e89b-42d3-a456-426614174000";

    @After
    public void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    public void shouldRejectDiagramListWhenWorkspaceHeaderIsMissing() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        inject(controller, "canvasStateStore", store);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        Response<List<DiagramSummaryResponseDTO>> response = controller.listDiagrams("admin");

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        assertFalse(store.listCalled);
    }

    @Test
    public void shouldLogMigrationHintWhenLegacyWorkspaceIdIsIgnored() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        inject(controller, "canvasStateStore", store);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        Logger logger = (Logger) LoggerFactory.getLogger("org.zipp.ai.trigger.http.CurrentOwnerHttpResolver");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            controller.listDiagrams("admin");

            assertTrue(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("Workspace id header missing")
                            && message.contains("X-Workspace-Id")
                            && !message.contains("admin")));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    public void shouldUseWorkspaceHeaderInsteadOfQueryUserId() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        inject(controller, "canvasStateStore", store);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", VALID_WORKSPACE_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Response<List<DiagramSummaryResponseDTO>> response = controller.listDiagrams("admin");

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertTrue(store.listCalled);
        assertEquals(VALID_WORKSPACE_ID, store.listedUserId);
    }

    @Test
    public void shouldRejectPredictableWorkspaceHeader() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        inject(controller, "canvasStateStore", store);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", "admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Response<List<DiagramSummaryResponseDTO>> response = controller.listDiagrams(null);

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        assertFalse(store.listCalled);
    }

    @Test
    public void shouldRejectDiagramBodyUserIdWhenWorkspaceHeaderIsMissing() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        inject(controller, "canvasStateStore", store);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        UpdateDiagramTitleRequestDTO requestDTO = new UpdateDiagramTitleRequestDTO();
        requestDTO.setUserId("admin");
        requestDTO.setTitle("New title");

        Response<DiagramSummaryResponseDTO> response = controller.renameDiagram("diagram-1", requestDTO);

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        assertFalse(store.renameCalled);
    }

    @Test
    public void shouldReturnAnonymousCurrentAccountStatus() {
        AgentServiceController controller = new AgentServiceController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", VALID_WORKSPACE_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Response<CurrentAccountResponseDTO> response = controller.currentAccount();

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(VALID_WORKSPACE_ID, response.getData().getOwnerId());
        assertEquals(OwnerType.ANONYMOUS.name(), response.getData().getOwnerType());
        assertEquals(AccountStatus.ANONYMOUS.name(), response.getData().getAccountStatus());
        assertFalse(response.getData().isAuthenticated());
        assertFalse(response.getData().isEmailVerified());
    }

    @Test
    public void shouldExposeAnonymousDemoQuotaOnCurrentAccountStatus() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        AnonymousDemoQuotaService quotaService = new AnonymousDemoQuotaService();
        quotaService.consume(VALID_WORKSPACE_ID);
        quotaService.consume(VALID_WORKSPACE_ID);
        inject(controller, "anonymousDemoQuotaService", quotaService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", VALID_WORKSPACE_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Response<CurrentAccountResponseDTO> response = controller.currentAccount();

        assertEquals(Integer.valueOf(5), response.getData().getDemoQuotaLimit());
        assertEquals(Integer.valueOf(2), response.getData().getDemoQuotaUsed());
        assertEquals(Integer.valueOf(3), response.getData().getDemoQuotaRemaining());
        assertFalse(response.getData().getDemoQuotaExhausted());
    }

    @Test
    public void shouldPreferAuthenticatedSessionOverAnonymousWorkspaceHeader() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        inject(controller, "canvasStateStore", store);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", VALID_WORKSPACE_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        authenticate("usr_session-owner");

        controller.listDiagrams(null);

        assertEquals("usr_session-owner", store.listedUserId);
    }

    @Test
    public void shouldExposeAuthenticatedOwnerViaCurrentAccountEndpoint() {
        AgentServiceController controller = new AgentServiceController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        authenticate("usr_alice");

        Response<CurrentAccountResponseDTO> response = controller.currentAccount();

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals("usr_alice", response.getData().getOwnerId());
        assertEquals(OwnerType.USER.name(), response.getData().getOwnerType());
        assertEquals(AccountStatus.ACTIVE.name(), response.getData().getAccountStatus());
        assertTrue(response.getData().isAuthenticated());
        assertTrue(response.getData().isEmailVerified());
    }

    @Test
    public void shouldExposeVerifiedUserDailyPlatformQuotaOnCurrentAccountEndpoint() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        VerifiedUserPlatformQuotaService quotaService = new VerifiedUserPlatformQuotaService(
                Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC));
        quotaService.consume("usr_alice");
        quotaService.consume("usr_alice");
        inject(controller, "verifiedUserPlatformQuotaService", quotaService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        authenticate("usr_alice");

        Response<CurrentAccountResponseDTO> response = controller.currentAccount();

        assertEquals(Integer.valueOf(20), response.getData().getPlatformDailyQuotaLimit());
        assertEquals(Integer.valueOf(2), response.getData().getPlatformDailyQuotaUsed());
        assertEquals(Integer.valueOf(18), response.getData().getPlatformDailyQuotaRemaining());
        assertFalse(response.getData().getPlatformDailyQuotaExhausted());
        assertEquals("2026-07-02", response.getData().getPlatformDailyQuotaDate());
    }

    @Test
    public void shouldRejectCurrentAccountStatusWhenWorkspaceHeaderIsInvalid() {
        AgentServiceController controller = new AgentServiceController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", "admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Response<CurrentAccountResponseDTO> response = controller.currentAccount();

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
    }

    private static void authenticate(String userId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class FakeCanvasStateStore implements ICanvasStateStore {

        private boolean listCalled;
        private String listedUserId;
        private boolean renameCalled;

        @Override
        public CanvasState save(CanvasState state) {
            return state;
        }

        @Override
        public java.util.Optional<CanvasState> find(String userId, String diagramId) {
            return java.util.Optional.empty();
        }

        @Override
        public List<CanvasState> list(String userId) {
            this.listCalled = true;
            this.listedUserId = userId;
            return List.of(CanvasState.builder()
                    .userId(userId)
                    .diagramId("diagram-1")
                    .title("Checkout Flow")
                    .build());
        }

        @Override
        public java.util.Optional<CanvasState> rename(String userId, String diagramId, String title) {
            this.renameCalled = true;
            return java.util.Optional.of(CanvasState.builder()
                    .userId(userId)
                    .diagramId(diagramId)
                    .title(title)
                    .build());
        }
    }
}
