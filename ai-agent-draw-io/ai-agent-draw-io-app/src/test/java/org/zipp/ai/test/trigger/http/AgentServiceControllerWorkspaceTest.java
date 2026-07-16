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
import org.zipp.ai.api.dto.DiagramCanvasStateResponseDTO;
import org.zipp.ai.api.dto.DiagramSummaryResponseDTO;
import org.zipp.ai.api.dto.ImportAnonymousWorkspaceRequestDTO;
import org.zipp.ai.api.dto.ImportAnonymousWorkspaceResponseDTO;
import org.zipp.ai.api.dto.SaveDiagramCanvasStateRequestDTO;
import org.zipp.ai.api.dto.UpdateDiagramThumbnailRequestDTO;
import org.zipp.ai.api.dto.UpdateDiagramTitleRequestDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.account.service.VerifiedUserPlatformQuotaService;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateVersionConflictException;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.canvas.CanvasMutationGate;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;
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
    private static final String VALID_PNG_DATA_URL = "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";

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
    public void shouldUpdateDiagramThumbnailFromWorkspaceHeader() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        inject(controller, "canvasStateStore", store);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", VALID_WORKSPACE_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UpdateDiagramThumbnailRequestDTO requestDTO = new UpdateDiagramThumbnailRequestDTO();
        requestDTO.setThumbnailDataUrl(VALID_PNG_DATA_URL);

        Response<DiagramSummaryResponseDTO> response = controller.updateDiagramThumbnail("diagram-1", requestDTO);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertTrue(store.thumbnailCalled);
        assertEquals(VALID_WORKSPACE_ID, store.thumbnailUserId);
        assertEquals("diagram-1", store.thumbnailDiagramId);
        assertEquals(VALID_PNG_DATA_URL, store.thumbnailUrl);
        assertEquals(VALID_PNG_DATA_URL, response.getData().getThumbnailUrl());
    }

    @Test
    public void shouldSaveManualDiagramCanvasFromWorkspaceHeader() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        inject(controller, "canvasStateStore", store);
        inject(controller, "canvasMutationGate", new CanvasMutationGate(store, new DefaultCanvasAnalyzer()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", VALID_WORKSPACE_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SaveDiagramCanvasStateRequestDTO requestDTO = new SaveDiagramCanvasStateRequestDTO();
        requestDTO.setCanvasXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" vertex=\"1\" parent=\"1\"><mxGeometry x=\"40\" y=\"40\" width=\"120\" height=\"60\" as=\"geometry\"/></mxCell></root></mxGraphModel>");
        requestDTO.setExpectedVersion(3L);

        Response<?> response = controller.saveDiagramCanvasState("diagram-1", requestDTO);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        DiagramCanvasStateResponseDTO savedState = (DiagramCanvasStateResponseDTO) response.getData();
        assertEquals("UPDATED", savedState.getSaveStatus());
        assertTrue(store.saveCalled);
        assertEquals(VALID_WORKSPACE_ID, store.savedState.getUserId());
        assertEquals("diagram-1", store.savedState.getDiagramId());
        assertEquals(Long.valueOf(3L), store.savedState.getVersion());
        assertEquals(requestDTO.getCanvasXml(), store.savedState.getCurrentXml());
    }

    @Test
    public void shouldReturnConflictWhenManualCanvasSaveVersionIsStale() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        store.conflictOnSave = true;
        store.currentState = CanvasState.builder()
                .userId(VALID_WORKSPACE_ID)
                .diagramId("diagram-1")
                .currentXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/></root></mxGraphModel>")
                .contentHash("sha256:current")
                .version(4L)
                .build();
        inject(controller, "canvasStateStore", store);
        inject(controller, "canvasMutationGate", new CanvasMutationGate(store, new DefaultCanvasAnalyzer()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", VALID_WORKSPACE_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SaveDiagramCanvasStateRequestDTO requestDTO = new SaveDiagramCanvasStateRequestDTO();
        requestDTO.setCanvasXml("<mxGraphModel><root><mxCell id=\"0\"/></root></mxGraphModel>");
        requestDTO.setExpectedVersion(1L);

        Response<?> response = controller.saveDiagramCanvasState("diagram-1", requestDTO);

        assertEquals(ResponseCode.CANVAS_VERSION_CONFLICT.getCode(), response.getCode());
        DiagramCanvasStateResponseDTO conflictState = (DiagramCanvasStateResponseDTO) response.getData();
        assertEquals(Long.valueOf(4L), conflictState.getVersion());
        assertEquals("diagram-1", conflictState.getDiagramId());
        assertEquals("sha256:current", conflictState.getContentHash());
    }

    @Test
    public void shouldRejectBlankOrOversizedManualCanvasXml() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        inject(controller, "canvasStateStore", store);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", VALID_WORKSPACE_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        SaveDiagramCanvasStateRequestDTO blankRequest = new SaveDiagramCanvasStateRequestDTO();
        blankRequest.setCanvasXml("   ");
        Response<?> blankResponse = controller.saveDiagramCanvasState("diagram-1", blankRequest);
        assertEquals(ResponseCode.INVALID_CANVAS_XML.getCode(), blankResponse.getCode());

        SaveDiagramCanvasStateRequestDTO oversizedRequest = new SaveDiagramCanvasStateRequestDTO();
        oversizedRequest.setCanvasXml(new String(new char[2 * 1024 * 1024 + 1]).replace('\0', 'x'));
        Response<?> oversizedResponse = controller.saveDiagramCanvasState("diagram-1", oversizedRequest);
        assertEquals(ResponseCode.INVALID_CANVAS_XML.getCode(), oversizedResponse.getCode());

        assertFalse(store.saveCalled);
    }

    @Test
    public void shouldRejectInvalidDiagramThumbnailDataUrl() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        inject(controller, "canvasStateStore", store);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", VALID_WORKSPACE_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UpdateDiagramThumbnailRequestDTO requestDTO = new UpdateDiagramThumbnailRequestDTO();
        requestDTO.setThumbnailDataUrl("data:text/plain;base64,SGVsbG8=");

        Response<DiagramSummaryResponseDTO> response = controller.updateDiagramThumbnail("diagram-1", requestDTO);

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        assertFalse(store.thumbnailCalled);
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
    public void shouldExposeOnlyCurrentUsersUsageSummaryOnCurrentAccountEndpoint() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        AgentUsageTelemetryService telemetryService = new AgentUsageTelemetryService(
                telemetryStore,
                Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC));
        telemetryService.startRun("usr_alice", "300000", "session-1", "chat",
                AgentUsageTelemetryService.PLATFORM, null, "openai", "gpt-5.5");
        telemetryService.startRun("usr_alice", "300000", "session-2", "chat",
                AgentUsageTelemetryService.USER_KEY, "mcr_alice", "openai", "gpt-4o");
        telemetryService.startRun("usr_bob", "300000", "session-3", "chat",
                AgentUsageTelemetryService.USER_KEY, "mcr_bob", "openai", "gpt-4o");
        inject(controller, "agentUsageTelemetryService", telemetryService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        authenticate("usr_alice");

        Response<CurrentAccountResponseDTO> response = controller.currentAccount();

        assertEquals(Long.valueOf(1), response.getData().getPlatformRunCount());
        assertEquals(Long.valueOf(1), response.getData().getUserKeyRunCount());
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

    @Test
    public void shouldRejectAnonymousWorkspaceImportWhenSessionIsMissing() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        inject(controller, "canvasStateStore", store);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        ImportAnonymousWorkspaceRequestDTO requestDTO = new ImportAnonymousWorkspaceRequestDTO();
        requestDTO.setAnonymousWorkspaceId(VALID_WORKSPACE_ID);

        Response<ImportAnonymousWorkspaceResponseDTO> response = controller.importAnonymousWorkspace(requestDTO);

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        assertFalse(store.importCalled);
    }

    @Test
    public void shouldRejectAnonymousWorkspaceImportWhenWorkspaceIdIsInvalid() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        inject(controller, "canvasStateStore", store);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        authenticate("usr_alice");
        ImportAnonymousWorkspaceRequestDTO requestDTO = new ImportAnonymousWorkspaceRequestDTO();
        requestDTO.setAnonymousWorkspaceId("admin");

        Response<ImportAnonymousWorkspaceResponseDTO> response = controller.importAnonymousWorkspace(requestDTO);

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        assertFalse(store.importCalled);
    }

    @Test
    public void shouldImportAnonymousWorkspaceIntoAuthenticatedOwner() throws Exception {
        AgentServiceController controller = new AgentServiceController();
        FakeCanvasStateStore store = new FakeCanvasStateStore();
        inject(controller, "canvasStateStore", store);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        authenticate("usr_alice");
        ImportAnonymousWorkspaceRequestDTO requestDTO = new ImportAnonymousWorkspaceRequestDTO();
        requestDTO.setAnonymousWorkspaceId(VALID_WORKSPACE_ID);

        Response<ImportAnonymousWorkspaceResponseDTO> response = controller.importAnonymousWorkspace(requestDTO);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertTrue(store.importCalled);
        assertEquals(VALID_WORKSPACE_ID, store.importedAnonymousOwnerId);
        assertEquals("usr_alice", store.importedTargetOwnerId);
        assertEquals(1, response.getData().getImportedCount());
        assertEquals("imported-diagram-1", response.getData().getDiagrams().get(0).getDiagramId());
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
        private boolean importCalled;
        private boolean thumbnailCalled;
        private boolean saveCalled;
        private boolean conflictOnSave;
        private String importedAnonymousOwnerId;
        private String importedTargetOwnerId;
        private String thumbnailUserId;
        private String thumbnailDiagramId;
        private String thumbnailUrl;
        private CanvasState savedState;
        private CanvasState currentState;

        @Override
        public CanvasState save(CanvasState state) {
            if (conflictOnSave) {
                throw new CanvasStateVersionConflictException(state.getUserId(), state.getDiagramId(), state.getVersion());
            }
            this.saveCalled = true;
            this.savedState = state;
            return CanvasState.builder()
                    .userId(state.getUserId())
                    .diagramId(state.getDiagramId())
                    .currentXml(state.getCurrentXml())
                    .version(state.getVersion() == null ? 1L : state.getVersion() + 1)
                    .build();
        }

        @Override
        public java.util.Optional<CanvasState> find(String userId, String diagramId) {
            return java.util.Optional.ofNullable(currentState);
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

        @Override
        public java.util.Optional<CanvasState> updateThumbnail(String userId, String diagramId, String thumbnailUrl) {
            this.thumbnailCalled = true;
            this.thumbnailUserId = userId;
            this.thumbnailDiagramId = diagramId;
            this.thumbnailUrl = thumbnailUrl;
            return java.util.Optional.of(CanvasState.builder()
                    .userId(userId)
                    .diagramId(diagramId)
                    .title("Checkout Flow")
                    .thumbnailUrl(thumbnailUrl)
                    .build());
        }

        @Override
        public List<CanvasState> importAnonymousWorkspace(String anonymousOwnerId, String targetOwnerId) {
            this.importCalled = true;
            this.importedAnonymousOwnerId = anonymousOwnerId;
            this.importedTargetOwnerId = targetOwnerId;
            return List.of(CanvasState.builder()
                    .userId(targetOwnerId)
                    .diagramId("imported-diagram-1")
                    .title("Imported checkout")
                    .build());
        }
    }
}
