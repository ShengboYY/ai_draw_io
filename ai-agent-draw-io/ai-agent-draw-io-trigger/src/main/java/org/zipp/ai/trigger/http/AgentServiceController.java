package org.zipp.ai.trigger.http;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.zipp.ai.api.IAgentService;
import org.zipp.ai.api.dto.*;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.account.model.valobj.DemoQuotaSnapshot;
import org.zipp.ai.domain.account.model.valobj.PlatformDailyQuotaSnapshot;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.account.service.VerifiedUserPlatformQuotaService;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateSaveResult;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationAuthorization;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationCommand;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationDecision;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationPurpose;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationStatus;
import org.zipp.ai.domain.agent.model.valobj.conversation.DiagramConversationMessage;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.IDiagramConversationStore;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.canvas.CanvasMutationGate;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.domain.citation.model.valobj.AnswerCitationView;
import org.zipp.ai.domain.citation.service.CitationQueryService;
import org.zipp.ai.trigger.http.service.AnonymousWorkspaceClaimService;
import org.zipp.ai.trigger.http.service.ManualCanvasCommitCoordinator;
import org.zipp.ai.trigger.http.turn.TurnV2ProductIngressAdapter;
import org.zipp.ai.types.enums.ResponseCode;
import org.zipp.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/")
public class AgentServiceController implements IAgentService {

    private static final String PNG_DATA_URL_PREFIX = "data:image/png;base64,";
    private static final int MAX_THUMBNAIL_BYTES = 512 * 1024;
    private static final int MAX_THUMBNAIL_DATA_URL_LENGTH = 750 * 1024;
    private static final int MAX_CANVAS_XML_LENGTH = 2 * 1024 * 1024;
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String RUN_ID_HEADER = "X-Agent-Run-Id";
    private static final String V2_INGRESS_NOT_READY = "TURN_V2_PRODUCT_INGRESS_NOT_READY";
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final Pattern CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    @Resource
    private IChatService chatService;

    @Resource
    private ICanvasStateStore canvasStateStore;

    @Resource
    private CanvasMutationGate canvasMutationGate;

    @Autowired(required = false)
    private ManualCanvasCommitCoordinator manualCanvasCommitCoordinator;

    @Autowired(required = false)
    private TurnV2ProductIngressAdapter turnV2ProductIngressAdapter;

    @Resource
    private IDiagramConversationStore diagramConversationStore;

    @Resource
    private CurrentOwnerHttpResolver currentOwnerHttpResolver;

    @Resource
    private AnonymousWorkspaceClaimService anonymousWorkspaceClaimService;

    @Resource
    private AnonymousWorkspaceCookie anonymousWorkspaceCookie;

    @Resource
    private AnonymousDemoQuotaService anonymousDemoQuotaService;

    @Resource
    private VerifiedUserPlatformQuotaService verifiedUserPlatformQuotaService;

    @Resource
    private AgentUsageTelemetryService agentUsageTelemetryService;

    @Autowired(required = false)
    private CitationQueryService citationQueryService;

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
        String workspaceId = resolveOwnerId(requestDTO == null ? null : requestDTO.getUserId());
        if (StringUtils.isBlank(workspaceId)) {
            return illegalWorkspaceResponse();
        }
        requestDTO.setUserId(workspaceId);
        try {
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), CurrentOwnerHttpResolver.mask(requestDTO.getUserId()));
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
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), CurrentOwnerHttpResolver.mask(requestDTO.getUserId()), e);
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
        String workspaceId = resolveOwnerId(userId);
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
            log.error("查询图列表失败 userId:{}", CurrentOwnerHttpResolver.mask(workspaceId), e);
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
        String workspaceId = resolveOwnerId(userId);
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
            log.error("查询图详情失败 userId:{} diagramId:{}", CurrentOwnerHttpResolver.mask(workspaceId), diagramId, e);
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
        String workspaceId = resolveOwnerId(requestDTO == null ? null : requestDTO.getUserId());
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
            log.error("重命名图失败 userId:{} diagramId:{}", CurrentOwnerHttpResolver.mask(workspaceId), diagramId, e);
            return Response.<DiagramSummaryResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "diagrams/{diagramId}/thumbnail", method = RequestMethod.PATCH)
    @Override
    public Response<DiagramSummaryResponseDTO> updateDiagramThumbnail(
            @PathVariable("diagramId") String diagramId,
            @RequestBody UpdateDiagramThumbnailRequestDTO requestDTO) {
        String workspaceId = resolveOwnerId(requestDTO == null ? null : requestDTO.getUserId());
        String thumbnailDataUrl = normalizeThumbnailDataUrl(requestDTO == null ? null : requestDTO.getThumbnailDataUrl());
        if (StringUtils.isBlank(workspaceId) || StringUtils.isBlank(diagramId) || thumbnailDataUrl == null) {
            return illegalWorkspaceResponse();
        }
        try {
            CanvasState state = canvasStateStore.updateThumbnail(workspaceId, diagramId, thumbnailDataUrl).orElse(null);
            if (state != null) {
                // Attach the freshly exported thumbnail to the trace snapshot whose canvas it depicts.
                telemetryService().backfillDiagramSnapshotThumbnail(
                        workspaceId, diagramId, state.getContentHash(), state.getThumbnailUrl());
            }
            DiagramSummaryResponseDTO diagram = state == null ? null : toDiagramSummary(state);
            return Response.<DiagramSummaryResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(diagram)
                    .build();
        } catch (Exception e) {
            log.error("保存图缩略图失败 userId:{} diagramId:{}", CurrentOwnerHttpResolver.mask(workspaceId), diagramId, e);
            return Response.<DiagramSummaryResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "diagrams/{diagramId}/canvas", method = RequestMethod.PATCH)
    @Override
    public Response<DiagramCanvasStateResponseDTO> saveDiagramCanvasState(
            @PathVariable("diagramId") String diagramId,
            @RequestBody SaveDiagramCanvasStateRequestDTO requestDTO) {
        String workspaceId = resolveOwnerId(requestDTO == null ? null : requestDTO.getUserId());
        String canvasXml = requestDTO == null ? null : requestDTO.getCanvasXml();
        if (StringUtils.isBlank(workspaceId) || StringUtils.isBlank(diagramId)) {
            return illegalWorkspaceResponse();
        }
        if (StringUtils.isBlank(canvasXml) || canvasXml.length() > MAX_CANVAS_XML_LENGTH) {
            return Response.<DiagramCanvasStateResponseDTO>builder()
                    .code(ResponseCode.INVALID_CANVAS_XML.getCode())
                    .info(ResponseCode.INVALID_CANVAS_XML.getInfo())
                    .build();
        }
        try {
            CanvasState current = canvasStateStore.find(workspaceId, diagramId).orElse(null);
            boolean manualEditAfterRepair = isManualEditAfterVisualRepair(
                    requestDTO, workspaceId, diagramId, current);
            CanvasMutationCommand mutationCommand = new CanvasMutationCommand(
                    current == null ? CanvasMutationPurpose.USER_CREATE : CanvasMutationPurpose.USER_EDIT,
                    current == null ? "" : current.getCurrentXml(),
                    canvasXml,
                    DiagramType.from(current == null ? null : current.getDiagramType()),
                    CanvasMutationAuthorization.unrestricted(),
                    workspaceId,
                    diagramId,
                    requestDTO.getExpectedVersion(),
                    requestDTO.getExpectedContentHash());
            CanvasMutationDecision decision = manualCanvasCommitCoordinator == null
                    ? canvasMutationGate.evaluate(mutationCommand)
                    : manualCanvasCommitCoordinator.commit(mutationCommand);
            if (decision.status() == CanvasMutationStatus.STALE_VERSION) {
                return Response.<DiagramCanvasStateResponseDTO>builder()
                        .code(ResponseCode.CANVAS_VERSION_CONFLICT.getCode())
                        .info(ResponseCode.CANVAS_VERSION_CONFLICT.getInfo())
                        .data(decision.currentState() == null ? null : toDiagramCanvasState(decision.currentState()))
                        .build();
            }
            if (decision.status() != CanvasMutationStatus.ACCEPTED
                    && decision.status() != CanvasMutationStatus.ACCEPTED_WITH_NOTES) {
                return Response.<DiagramCanvasStateResponseDTO>builder()
                        .code(ResponseCode.INVALID_CANVAS_XML.getCode())
                        .info(ResponseCode.INVALID_CANVAS_XML.getInfo())
                        .build();
            }
            CanvasStateSaveResult saved = decision.saveResult();
            if (manualEditAfterRepair && !decision.changedCellIds().isEmpty()) {
                telemetryService().recordManualEditAfterVisualRepair(requestDTO.getVisualRepairRound());
                log.info("[visual-review-loop] event=manual_edit_after_repair diagramId={} repairRound={}",
                        diagramId, requestDTO.getVisualRepairRound());
            }
            return Response.<DiagramCanvasStateResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toDiagramCanvasState(saved))
                    .build();
        } catch (Exception e) {
            log.error("保存图画布失败 userId:{} diagramId:{}", CurrentOwnerHttpResolver.mask(workspaceId), diagramId, e);
            return Response.<DiagramCanvasStateResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private boolean isManualEditAfterVisualRepair(SaveDiagramCanvasStateRequestDTO requestDTO,
                                                   String workspaceId,
                                                   String diagramId,
                                                   CanvasState current) {
        if (requestDTO == null || current == null || current.getVersion() == null
                || StringUtils.isBlank(current.getContentHash())
                || requestDTO.getVisualRepairRound() == null
                || requestDTO.getVisualRepairRound() < 1
                || requestDTO.getVisualRepairRound() > 2
                || !validCorrelationId(requestDTO.getVisualRepairSourceRunId())
                || !validCorrelationId(requestDTO.getVisualRepairRunId())) {
            return false;
        }
        // Treat lineage supplied by the browser as a claim only; the durable repair claim and
        // exact current version/hash are the authority for whether this is a post-repair edit.
        return telemetryService().isVisualRepairResult(
                requestDTO.getVisualRepairSourceRunId(), requestDTO.getVisualRepairRunId(),
                workspaceId, diagramId, current.getVersion(), current.getContentHash(),
                requestDTO.getVisualRepairRound());
    }

    private boolean validCorrelationId(String value) {
        return StringUtils.isNotBlank(value) && CORRELATION_ID.matcher(value).matches();
    }

    @RequestMapping(value = "diagrams/{diagramId}", method = RequestMethod.DELETE)
    @Override
    public Response<Boolean> deleteDiagram(@RequestParam(value = "userId", required = false) String userId,
                                           @PathVariable("diagramId") String diagramId) {
        String workspaceId = resolveOwnerId(userId);
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
            log.error("删除图失败 userId:{} diagramId:{}", CurrentOwnerHttpResolver.mask(workspaceId), diagramId, e);
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
        String workspaceId = resolveOwnerId(userId);
        if (StringUtils.isBlank(workspaceId)) {
            return illegalWorkspaceResponse();
        }
        try {
            List<DiagramConversationMessageDTO> messages = diagramConversationStore.listMessages(workspaceId, diagramId).stream()
                    .map(this::toDiagramConversationMessageDTO)
                    .collect(Collectors.toList());
            attachAnswerCitations(workspaceId, diagramId, messages);
            return Response.<List<DiagramConversationMessageDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(messages)
                    .build();
        } catch (Exception e) {
            log.error("查询图会话消息失败 userId:{} diagramId:{}", CurrentOwnerHttpResolver.mask(workspaceId), diagramId, e);
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
        String userId = resolveOwnerId(requestDTO == null ? null : requestDTO.getUserId());
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
            log.error("保存图会话消息失败 userId:{} diagramId:{}", CurrentOwnerHttpResolver.mask(userId), diagramId, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @Override
    public Response<ChatResponseDTO> chat(ChatRequestDTO requestDTO) {
        return chatWithCorrelation(requestDTO, null, null);
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO,
                                          @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
                                          HttpServletResponse httpResponse) {
        return chatWithCorrelation(requestDTO, requestId, httpResponse);
    }

    private Response<ChatResponseDTO> chatWithCorrelation(ChatRequestDTO requestDTO,
                                                          String requestIdHeader,
                                                          HttpServletResponse httpResponse) {
        String requestId = resolveRequestId(requestIdHeader, requestDTO);
        String runId = newRunId();
        writeCorrelationHeaders(httpResponse, requestId, runId);
        String workspaceId = resolveOwnerId(requestDTO == null ? null : requestDTO.getUserId());
        if (StringUtils.isBlank(workspaceId)) {
            return illegalWorkspaceResponse();
        }
        requestDTO.setUserId(workspaceId);
        applyCorrelation(requestDTO, requestId, runId);
        if (turnV2ProductIngressAdapter == null) {
            // Product chat is fail-closed after the Legacy executor is removed.
            return Response.<ChatResponseDTO>builder()
                    .code(V2_INGRESS_NOT_READY)
                    .info(V2_INGRESS_NOT_READY)
                    .build();
        }
        try {
            ChatResponseDTO responseDTO = turnV2ProductIngressAdapter.chat(
                    workspaceId, requestDTO, requestId, runId);
            responseDTO.setRequestId(requestId);
            responseDTO.setRunId(runId);
            writeCorrelationHeaders(httpResponse, requestId, runId);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常 requestId:{} runId:{}", requestId, runId, e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话败 agentId:{} userId:{} requestId:{} runId:{}",
                    requestDTO.getAgentId(), CurrentOwnerHttpResolver.mask(requestDTO.getUserId()), requestId, runId, e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @Override
    public ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO) {
        return chatStreamWithCorrelation(requestDTO, null);
    }

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO,
                                          @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId) {
        return chatStreamWithCorrelation(requestDTO, requestId);
    }

    private ResponseBodyEmitter chatStreamWithCorrelation(ChatRequestDTO requestDTO, String requestIdHeader) {
        String requestId = resolveRequestId(requestIdHeader, requestDTO);
        String runId = newRunId();
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(10 * 60 * 1000L) {
            @Override
            protected void extendResponse(ServerHttpResponse outputMessage) {
                outputMessage.getHeaders().set("Content-Type", "application/x-ndjson");
                writeCorrelationHeaders(outputMessage, requestId, runId);
            }
        };
        String workspaceId = resolveOwnerId(requestDTO == null ? null : requestDTO.getUserId());
        if (StringUtils.isBlank(workspaceId)) {
            emitter.completeWithError(new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "Missing or invalid workspace id"));
            return emitter;
        }
        requestDTO.setUserId(workspaceId);
        applyCorrelation(requestDTO, requestId, runId);
        if (turnV2ProductIngressAdapter == null) {
            sendV2IngressError(emitter, V2_INGRESS_NOT_READY);
            return emitter;
        }
        turnV2ProductIngressAdapter.stream(workspaceId, requestDTO, requestId, runId, emitter);
        return emitter;
    }

    @RequestMapping(value = "account/me", method = RequestMethod.GET)
    public Response<CurrentAccountResponseDTO> currentAccount() {
        return ownerHttpResolver().resolve(null)
                .map(owner -> Response.<CurrentAccountResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(toCurrentAccountResponse(owner))
                        .build())
                .orElseGet(this::illegalWorkspaceResponse);
    }

    @RequestMapping(value = "workspaces/anonymous/import", method = RequestMethod.POST)
    public Response<ImportAnonymousWorkspaceResponseDTO> importAnonymousWorkspace(
            @RequestBody(required = false) ImportAnonymousWorkspaceRequestDTO requestDTO,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        ResolvedOwner owner = ownerHttpResolver().resolve(null).orElse(null);
        if (owner == null || OwnerType.USER != owner.getOwnerType() || !owner.isAuthenticated()) {
            return illegalWorkspaceResponse();
        }

        String rawCredential = anonymousWorkspaceCookie == null
                ? null
                : anonymousWorkspaceCookie.read(servletRequest).orElse(null);
        if (StringUtils.isBlank(rawCredential) || anonymousWorkspaceClaimService == null) {
            return illegalWorkspaceResponse();
        }

        try {
            // The request body is intentionally ignored: only possession of the server-issued
            // HttpOnly credential authorizes migration from an anonymous owner.
            List<CanvasState> imported = anonymousWorkspaceClaimService.claim(
                    rawCredential, owner.getOwnerId());
            anonymousWorkspaceCookie.clear(servletResponse);
            ImportAnonymousWorkspaceResponseDTO responseDTO = new ImportAnonymousWorkspaceResponseDTO();
            responseDTO.setImportedCount(imported.size());
            responseDTO.setDiagrams(imported.stream()
                    .map(this::toDiagramSummary)
                    .collect(Collectors.toList()));
            return Response.<ImportAnonymousWorkspaceResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("导入匿名工作区失败 targetOwnerId:{}",
                    CurrentOwnerHttpResolver.mask(owner.getOwnerId()), e);
            return Response.<ImportAnonymousWorkspaceResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /** Compatibility overload for direct controller tests; it does not restore body-id authority. */
    public Response<ImportAnonymousWorkspaceResponseDTO> importAnonymousWorkspace(
            ImportAnonymousWorkspaceRequestDTO requestDTO) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return illegalWorkspaceResponse();
        }
        return importAnonymousWorkspace(
                requestDTO, attributes.getRequest(), attributes.getResponse());
    }

    private DiagramSummaryResponseDTO toDiagramSummary(CanvasState state) {
        DiagramSummaryResponseDTO dto = new DiagramSummaryResponseDTO();
        dto.setDiagramId(state.getDiagramId());
        dto.setTitle(state.getTitle());
        dto.setDiagramType(state.getDiagramType());
        dto.setThumbnailUrl(state.getThumbnailUrl());
        dto.setVersion(state.getVersion());
        dto.setUpdatedAt(state.getUpdatedAt());
        return dto;
    }

    private DiagramCanvasStateResponseDTO toDiagramCanvasState(CanvasState state) {
        return toDiagramCanvasState(state, null);
    }

    private DiagramCanvasStateResponseDTO toDiagramCanvasState(CanvasStateSaveResult result) {
        if (result == null) {
            return null;
        }
        return toDiagramCanvasState(result.getState(), result.getStatus() == null ? null : result.getStatus().name());
    }

    private DiagramCanvasStateResponseDTO toDiagramCanvasState(CanvasState state, String saveStatus) {
        if (state == null) {
            return null;
        }
        DiagramCanvasStateResponseDTO dto = new DiagramCanvasStateResponseDTO();
        dto.setDiagramId(state.getDiagramId());
        dto.setUserId(state.getUserId());
        dto.setTitle(state.getTitle());
        dto.setDiagramType(state.getDiagramType());
        dto.setThumbnailUrl(state.getThumbnailUrl());
        dto.setCurrentXml(state.getCurrentXml());
        dto.setContentHash(state.getContentHash());
        dto.setSaveStatus(saveStatus);
        dto.setSummary(state.getSummary());
        dto.setVersion(state.getVersion());
        dto.setUpdatedAt(state.getUpdatedAt());
        return dto;
    }

    private DiagramConversationMessageDTO toDiagramConversationMessageDTO(DiagramConversationMessage message) {
        DiagramConversationMessageDTO dto = new DiagramConversationMessageDTO();
        dto.setClientMessageId(message.getClientMessageId());
        dto.setTurnId(message.getTurnId());
        dto.setSessionId(message.getSessionId());
        dto.setRole(message.getRole());
        dto.setContent(message.getContent());
        dto.setAttachmentRefs(message.getAttachmentRefs());
        dto.setCreatedAt(message.getCreatedAt());
        return dto;
    }

    private void attachAnswerCitations(String ownerKey, String diagramId,
                                       List<DiagramConversationMessageDTO> messages) {
        if (citationQueryService == null || messages.isEmpty()) return;
        List<AnswerCitationView> citations = citationQueryService.findAnswers(ownerKey, diagramId,
                messages.stream().map(DiagramConversationMessageDTO::getClientMessageId).toList());
        Map<String, List<AnswerCitationView>> byMessage = citations.stream()
                .collect(Collectors.groupingBy(AnswerCitationView::messageId, LinkedHashMap::new, Collectors.toList()));
        for (DiagramConversationMessageDTO message : messages) {
            List<AnswerCitationView> claims = byMessage.getOrDefault(message.getClientMessageId(), List.of());
            if (claims.isEmpty()) continue;
            List<DiagramConversationMessageDTO.EvidenceClaimDTO> claimDtos = new ArrayList<>();
            LinkedHashMap<String, DiagramConversationMessageDTO.EvidenceSourceDTO> sourceDtos = new LinkedHashMap<>();
            for (AnswerCitationView claim : claims) {
                DiagramConversationMessageDTO.EvidenceClaimDTO claimDto = new DiagramConversationMessageDTO.EvidenceClaimDTO();
                claimDto.setClaimKey(claim.claimKey());
                claimDto.setSupportType(claim.supportType());
                claimDto.setCitationKeys(claim.sources().stream().map(AnswerCitationView.AnswerSourceView::citationKey).toList());
                claimDtos.add(claimDto);
                for (AnswerCitationView.AnswerSourceView source : claim.sources()) {
                    sourceDtos.computeIfAbsent(source.citationKey(), ignored -> {
                        DiagramConversationMessageDTO.EvidenceSourceDTO sourceDto = new DiagramConversationMessageDTO.EvidenceSourceDTO();
                        sourceDto.setCitationKey(source.citationKey());
                        sourceDto.setSourceLabel(source.sourceLabel());
                        sourceDto.setPageNumber(source.pageNumber());
                        sourceDto.setModality(source.modality());
                        sourceDto.setOrigin(source.origin());
                        return sourceDto;
                    });
                }
            }
            message.setEvidenceClaims(List.copyOf(claimDtos));
            message.setEvidenceSources(List.copyOf(sourceDtos.values()));
        }
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
                .attachmentRefs(message.getAttachmentRefs() == null ? List.of() : message.getAttachmentRefs())
                .build();
    }

    private CurrentAccountResponseDTO toCurrentAccountResponse(ResolvedOwner owner) {
        CurrentAccountResponseDTO dto = new CurrentAccountResponseDTO();
        dto.setOwnerId(owner.getOwnerId());
        dto.setOwnerType(owner.getOwnerType().name());
        dto.setAuthenticated(owner.isAuthenticated());
        dto.setEmailVerified(owner.isEmailVerified());
        dto.setAccountStatus(owner.getAccountStatus().name());
        if (OwnerType.ANONYMOUS == owner.getOwnerType()) {
            DemoQuotaSnapshot quota = demoQuotaService().snapshot(owner.getOwnerId());
            dto.setDemoQuotaLimit(quota.getLimit());
            dto.setDemoQuotaUsed(quota.getUsed());
            dto.setDemoQuotaRemaining(quota.getRemaining());
            dto.setDemoQuotaExhausted(quota.isExhausted());
        } else if (OwnerType.USER == owner.getOwnerType()) {
            PlatformDailyQuotaSnapshot quota = platformQuotaService().snapshot(owner.getOwnerId());
            dto.setPlatformDailyQuotaLimit(quota.getLimit());
            dto.setPlatformDailyQuotaUsed(quota.getUsed());
            dto.setPlatformDailyQuotaRemaining(quota.getRemaining());
            dto.setPlatformDailyQuotaExhausted(quota.isExhausted());
            dto.setPlatformDailyQuotaDate(quota.getQuotaDate());
        }
        AgentUsageSummary usage = telemetryService().summarizeForUser(owner.getOwnerId());
        dto.setPlatformRunCount(usage.getPlatformRunCount());
        dto.setUserKeyRunCount(usage.getUserKeyRunCount());
        dto.setPlatformLlmCallCount(usage.getPlatformLlmCallCount());
        dto.setUserKeyLlmCallCount(usage.getUserKeyLlmCallCount());
        dto.setToolCallCount(usage.getToolCallCount());
        dto.setKnownTotalTokens(usage.getKnownTotalTokens());
        dto.setUnknownTokenLlmCallCount(usage.getUnknownTokenLlmCallCount());
        return dto;
    }

    private String resolveOwnerId(String legacyOwnerId) {
        return ownerHttpResolver().resolveOwnerId(legacyOwnerId).orElse(null);
    }

    private String normalizeThumbnailDataUrl(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_THUMBNAIL_DATA_URL_LENGTH || !trimmed.startsWith(PNG_DATA_URL_PREFIX)) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(trimmed.substring(PNG_DATA_URL_PREFIX.length()));
            if (bytes.length == 0 || bytes.length > MAX_THUMBNAIL_BYTES || !hasPngSignature(bytes)) {
                return null;
            }
            return trimmed;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean hasPngSignature(byte[] bytes) {
        byte[] signature = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (bytes.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private String resolveRequestId(String requestIdHeader, ChatRequestDTO requestDTO) {
        String candidate = StringUtils.defaultIfBlank(requestIdHeader, requestDTO == null ? null : requestDTO.getRequestId());
        String trimmed = StringUtils.trimToNull(candidate);
        return trimmed != null && CORRELATION_ID.matcher(trimmed).matches()
                ? trimmed
                : "req-" + UUID.randomUUID();
    }

    private String newRunId() {
        return telemetryService().newRunId();
    }

    private void applyCorrelation(ChatRequestDTO requestDTO, String requestId, String runId) {
        if (requestDTO == null) {
            return;
        }
        requestDTO.setRequestId(requestId);
        requestDTO.setRunId(runId);
    }

    private void writeCorrelationHeaders(HttpServletResponse response, String requestId, String runId) {
        if (response == null) {
            return;
        }
        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.setHeader(RUN_ID_HEADER, StringUtils.defaultIfBlank(runId, ""));
    }

    private void writeCorrelationHeaders(ServerHttpResponse response, String requestId, String runId) {
        if (response == null) {
            return;
        }
        response.getHeaders().set(REQUEST_ID_HEADER, requestId);
        response.getHeaders().set(RUN_ID_HEADER, StringUtils.defaultIfBlank(runId, ""));
    }

    private void sendV2IngressError(ResponseBodyEmitter emitter, String errorCode) {
        try {
            // Keep startup/configuration failures inside the same NDJSON contract as V2 turns.
            JSONObject chunk = new JSONObject();
            chunk.put("type", "error");
            chunk.put("content", errorCode);
            chunk.put("code", errorCode);
            JSONObject event = new JSONObject();
            event.put("phase", "error");
            event.put("chunk", chunk);
            emitter.send(JSON.toJSONString(event) + "\n", NDJSON);
            emitter.complete();
        } catch (IOException failure) {
            emitter.completeWithError(failure);
        }
    }

    private CurrentOwnerHttpResolver ownerHttpResolver() {
        return currentOwnerHttpResolver == null ? new CurrentOwnerHttpResolver() : currentOwnerHttpResolver;
    }

    private AnonymousDemoQuotaService demoQuotaService() {
        return anonymousDemoQuotaService == null ? new AnonymousDemoQuotaService() : anonymousDemoQuotaService;
    }

    private VerifiedUserPlatformQuotaService platformQuotaService() {
        return verifiedUserPlatformQuotaService == null ? new VerifiedUserPlatformQuotaService() : verifiedUserPlatformQuotaService;
    }

    private AgentUsageTelemetryService telemetryService() {
        return agentUsageTelemetryService == null ? new AgentUsageTelemetryService(null) : agentUsageTelemetryService;
    }

    private <T> Response<T> illegalWorkspaceResponse() {
        return Response.<T>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info("Missing or invalid workspace id")
                .build();
    }

}
