package org.zipp.ai.trigger.http;

import org.zipp.ai.api.IAgentService;
import org.zipp.ai.api.dto.*;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.conversation.DiagramConversationMessage;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.IDiagramConversationStore;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.trigger.http.service.AgentConversationService;
import org.zipp.ai.types.enums.ResponseCode;
import org.zipp.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    @Resource
    private AgentConversationService agentConversationService;

    @Resource
    private ICanvasStateStore canvasStateStore;

    @Resource
    private IDiagramConversationStore diagramConversationStore;

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");

            List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();

            List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                responseDTO.setAgentId(agentConfig.getAgentId());
                responseDTO.setAgentName(agentConfig.getAgentName());
                responseDTO.setAgentDesc(agentConfig.getAgentDesc());
                return responseDTO;
            }).collect(Collectors.toList());

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        String workspaceId = WorkspaceIds.resolve(requestDTO == null ? null : requestDTO.getUserId());
        if (StringUtils.isBlank(workspaceId)) {
            return illegalWorkspaceResponse();
        }
        requestDTO.setUserId(workspaceId);
        try {
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), WorkspaceIds.mask(requestDTO.getUserId()));
            String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), WorkspaceIds.mask(requestDTO.getUserId()), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(@RequestParam("agentId") String agentId,
                                                            @RequestParam(value = "userId", required = false) String userId) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        requestDTO.setUserId(userId);
        return createSession(requestDTO);
    }

    @RequestMapping(value = "diagrams", method = RequestMethod.GET)
    @Override
    public Response<List<DiagramSummaryResponseDTO>> listDiagrams(@RequestParam(value = "userId", required = false) String userId) {
        String workspaceId = WorkspaceIds.resolve(userId);
        if (StringUtils.isBlank(workspaceId)) {
            return illegalWorkspaceResponse();
        }
        try {
            List<DiagramSummaryResponseDTO> diagrams = canvasStateStore.list(workspaceId).stream()
                    .map(this::toDiagramSummary)
                    .collect(Collectors.toList());
            return Response.<List<DiagramSummaryResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(diagrams)
                    .build();
        } catch (Exception e) {
            log.error("查询图列表失败 userId:{}", WorkspaceIds.mask(workspaceId), e);
            return Response.<List<DiagramSummaryResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "diagrams/{diagramId}", method = RequestMethod.GET)
    @Override
    public Response<DiagramCanvasStateResponseDTO> getDiagram(@RequestParam(value = "userId", required = false) String userId,
                                                              @PathVariable("diagramId") String diagramId) {
        String workspaceId = WorkspaceIds.resolve(userId);
        if (StringUtils.isBlank(workspaceId)) {
            return illegalWorkspaceResponse();
        }
        try {
            DiagramCanvasStateResponseDTO diagram = canvasStateStore.find(workspaceId, diagramId)
                    .map(this::toDiagramCanvasState)
                    .orElse(null);
            return Response.<DiagramCanvasStateResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(diagram)
                    .build();
        } catch (Exception e) {
            log.error("查询图详情失败 userId:{} diagramId:{}", WorkspaceIds.mask(workspaceId), diagramId, e);
            return Response.<DiagramCanvasStateResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "diagrams/{diagramId}/title", method = RequestMethod.PATCH)
    @Override
    public Response<DiagramSummaryResponseDTO> renameDiagram(@PathVariable("diagramId") String diagramId,
                                                             @RequestBody UpdateDiagramTitleRequestDTO requestDTO) {
        String workspaceId = WorkspaceIds.resolve(requestDTO == null ? null : requestDTO.getUserId());
        if (StringUtils.isBlank(workspaceId)) {
            return illegalWorkspaceResponse();
        }
        try {
            DiagramSummaryResponseDTO diagram = canvasStateStore.rename(workspaceId, diagramId, requestDTO.getTitle())
                    .map(this::toDiagramSummary)
                    .orElse(null);
            return Response.<DiagramSummaryResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(diagram)
                    .build();
        } catch (Exception e) {
            log.error("重命名图失败 userId:{} diagramId:{}", WorkspaceIds.mask(workspaceId), diagramId, e);
            return Response.<DiagramSummaryResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "diagrams/{diagramId}", method = RequestMethod.DELETE)
    @Override
    public Response<Boolean> deleteDiagram(@RequestParam(value = "userId", required = false) String userId,
                                           @PathVariable("diagramId") String diagramId) {
        String workspaceId = WorkspaceIds.resolve(userId);
        if (StringUtils.isBlank(workspaceId)) {
            return illegalWorkspaceResponse();
        }
        try {
            boolean deleted = canvasStateStore.softDelete(workspaceId, diagramId);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(deleted)
                    .build();
        } catch (Exception e) {
            log.error("删除图失败 userId:{} diagramId:{}", WorkspaceIds.mask(workspaceId), diagramId, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "diagrams/{diagramId}/messages", method = RequestMethod.GET)
    @Override
    public Response<List<DiagramConversationMessageDTO>> listDiagramMessages(@RequestParam(value = "userId", required = false) String userId,
                                                                             @PathVariable("diagramId") String diagramId) {
        String workspaceId = WorkspaceIds.resolve(userId);
        if (StringUtils.isBlank(workspaceId)) {
            return illegalWorkspaceResponse();
        }
        try {
            List<DiagramConversationMessageDTO> messages = diagramConversationStore.listMessages(workspaceId, diagramId).stream()
                    .map(this::toDiagramConversationMessageDTO)
                    .collect(Collectors.toList());
            return Response.<List<DiagramConversationMessageDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(messages)
                    .build();
        } catch (Exception e) {
            log.error("查询图会话消息失败 userId:{} diagramId:{}", WorkspaceIds.mask(workspaceId), diagramId, e);
            return Response.<List<DiagramConversationMessageDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "diagrams/{diagramId}/messages", method = RequestMethod.POST)
    @Override
    public Response<Boolean> saveDiagramMessages(@PathVariable("diagramId") String diagramId,
                                                 @RequestBody SaveDiagramMessagesRequestDTO requestDTO) {
        String userId = WorkspaceIds.resolve(requestDTO == null ? null : requestDTO.getUserId());
        if (StringUtils.isBlank(userId)) {
            return illegalWorkspaceResponse();
        }
        if (requestDTO != null) {
            requestDTO.setUserId(userId);
        }
        try {
            List<DiagramConversationMessageDTO> requestMessages = requestDTO == null || requestDTO.getMessages() == null
                    ? List.of()
                    : requestDTO.getMessages();
            List<DiagramConversationMessage> messages = requestMessages.stream()
                    .filter(message -> message != null)
                    .map(message -> toDiagramConversationMessage(requestDTO, diagramId, message))
                    .collect(Collectors.toList());
            diagramConversationStore.saveMessages(messages);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("保存图会话消息失败 userId:{} diagramId:{}", WorkspaceIds.mask(userId), diagramId, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        String workspaceId = WorkspaceIds.resolve(requestDTO == null ? null : requestDTO.getUserId());
        if (StringUtils.isBlank(workspaceId)) {
            return illegalWorkspaceResponse();
        }
        requestDTO.setUserId(workspaceId);
        try {
            log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), WorkspaceIds.mask(requestDTO.getUserId()));
            ChatResponseDTO responseDTO = agentConversationService.chat(requestDTO);

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话败 agentId:{} userId:{}", requestDTO.getAgentId(), WorkspaceIds.mask(requestDTO.getUserId()), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(10 * 60 * 1000L) {
            @Override
            protected void extendResponse(org.springframework.http.server.ServerHttpResponse outputMessage) {
                outputMessage.getHeaders().set("Content-Type", "application/x-ndjson");
            }
        };
        String workspaceId = WorkspaceIds.resolve(requestDTO == null ? null : requestDTO.getUserId());
        if (StringUtils.isBlank(workspaceId)) {
            emitter.completeWithError(new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "Missing or invalid workspace id"));
            return emitter;
        }
        requestDTO.setUserId(workspaceId);
        try {
            log.info("流式对话 agentId:{} userId:{} sessionId:{} message:{}", requestDTO.getAgentId(), WorkspaceIds.mask(requestDTO.getUserId()), requestDTO.getSessionId(), requestDTO.getMessage());
            agentConversationService.stream(requestDTO, emitter);
        } catch (Exception e) {
            log.error("流式对话失败", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private DiagramSummaryResponseDTO toDiagramSummary(CanvasState state) {
        DiagramSummaryResponseDTO dto = new DiagramSummaryResponseDTO();
        dto.setDiagramId(state.getDiagramId());
        dto.setTitle(state.getTitle());
        dto.setDiagramType(state.getDiagramType());
        dto.setVersion(state.getVersion());
        dto.setUpdatedAt(state.getUpdatedAt());
        return dto;
    }

    private DiagramCanvasStateResponseDTO toDiagramCanvasState(CanvasState state) {
        DiagramCanvasStateResponseDTO dto = new DiagramCanvasStateResponseDTO();
        dto.setDiagramId(state.getDiagramId());
        dto.setUserId(state.getUserId());
        dto.setTitle(state.getTitle());
        dto.setDiagramType(state.getDiagramType());
        dto.setCurrentXml(state.getCurrentXml());
        dto.setSummary(state.getSummary());
        dto.setVersion(state.getVersion());
        dto.setUpdatedAt(state.getUpdatedAt());
        return dto;
    }

    private DiagramConversationMessageDTO toDiagramConversationMessageDTO(DiagramConversationMessage message) {
        DiagramConversationMessageDTO dto = new DiagramConversationMessageDTO();
        dto.setClientMessageId(message.getClientMessageId());
        dto.setSessionId(message.getSessionId());
        dto.setRole(message.getRole());
        dto.setContent(message.getContent());
        dto.setCreatedAt(message.getCreatedAt());
        return dto;
    }

    private DiagramConversationMessage toDiagramConversationMessage(SaveDiagramMessagesRequestDTO requestDTO,
                                                                    String diagramId,
                                                                    DiagramConversationMessageDTO message) {
        String userId = requestDTO == null ? null : requestDTO.getUserId();
        String sessionId = requestDTO == null ? null : requestDTO.getSessionId();
        return DiagramConversationMessage.builder()
                .userId(userId)
                .diagramId(diagramId)
                .sessionId(message.getSessionId() == null ? sessionId : message.getSessionId())
                .clientMessageId(message.getClientMessageId())
                .role(message.getRole())
                .content(message.getContent())
                .build();
    }

    private <T> Response<T> illegalWorkspaceResponse() {
        return Response.<T>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info("Missing or invalid workspace id")
                .build();
    }

}
