package org.zipp.ai.api;

import org.zipp.ai.api.dto.*;
import org.zipp.ai.api.response.Response;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;

/**
 * 智能体服务接口
 */
public interface IAgentService {

    Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList();

    Response<CreateSessionResponseDTO> createSession(CreateSessionRequestDTO requestDTO);

    Response<List<DiagramSummaryResponseDTO>> listDiagrams(String userId);

    Response<DiagramCanvasStateResponseDTO> getDiagram(String userId, String diagramId);

    Response<DiagramSummaryResponseDTO> renameDiagram(String diagramId, UpdateDiagramTitleRequestDTO requestDTO);

    Response<DiagramSummaryResponseDTO> updateDiagramThumbnail(String diagramId, UpdateDiagramThumbnailRequestDTO requestDTO);

    Response<Boolean> deleteDiagram(String userId, String diagramId);

    Response<List<DiagramConversationMessageDTO>> listDiagramMessages(String userId, String diagramId);

    Response<Boolean> saveDiagramMessages(String diagramId, SaveDiagramMessagesRequestDTO requestDTO);

    Response<ChatResponseDTO> chat(ChatRequestDTO requestDTO);

    ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO);

}
