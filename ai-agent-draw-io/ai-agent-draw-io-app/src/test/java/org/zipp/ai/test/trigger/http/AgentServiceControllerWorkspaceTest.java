package org.zipp.ai.test.trigger.http;

import org.junit.After;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.zipp.ai.api.dto.DiagramSummaryResponseDTO;
import org.zipp.ai.api.dto.UpdateDiagramTitleRequestDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.trigger.http.AgentServiceController;
import org.zipp.ai.types.enums.ResponseCode;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentServiceControllerWorkspaceTest {

    private static final String VALID_WORKSPACE_ID = "anon_123e4567-e89b-42d3-a456-426614174000";

    @After
    public void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
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
