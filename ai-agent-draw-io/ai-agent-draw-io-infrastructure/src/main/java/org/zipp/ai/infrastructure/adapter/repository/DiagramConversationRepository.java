package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.agent.model.valobj.conversation.DiagramConversationMessage;
import org.zipp.ai.domain.agent.service.IDiagramConversationStore;
import org.zipp.ai.infrastructure.dao.IDiagramConversationMapper;
import org.zipp.ai.infrastructure.dao.po.DiagramConversationMessagePO;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class DiagramConversationRepository implements IDiagramConversationStore {

    @Resource
    private IDiagramConversationMapper diagramConversationMapper;

    @Override
    public List<DiagramConversationMessage> listMessages(String userId, String diagramId) {
        if (isBlank(userId) || isBlank(diagramId)) {
            return Collections.emptyList();
        }
        return diagramConversationMapper.selectMessages(userId, diagramId).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void saveMessages(List<DiagramConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        // clientMessageId lets frontend retries overwrite the same turn instead of duplicating messages.
        messages.stream()
                .filter(this::isValid)
                .map(this::toPo)
                .forEach(diagramConversationMapper::upsertMessage);
    }

    @Override
    public int deleteUserMessages(String userId) {
        if (isBlank(userId)) {
            return 0;
        }
        return diagramConversationMapper.deleteByUserId(userId);
    }

    private boolean isValid(DiagramConversationMessage message) {
        return message != null
                && !isBlank(message.getUserId())
                && !isBlank(message.getDiagramId())
                && !isBlank(message.getClientMessageId())
                && !isBlank(message.getRole())
                && !isBlank(message.getContent());
    }

    private DiagramConversationMessage toDomain(DiagramConversationMessagePO po) {
        return DiagramConversationMessage.builder()
                .userId(po.getUserId())
                .diagramId(po.getDiagramId())
                .sessionId(po.getSessionId())
                .clientMessageId(po.getClientMessageId())
                .role(po.getRole())
                .content(po.getContent())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private DiagramConversationMessagePO toPo(DiagramConversationMessage message) {
        DiagramConversationMessagePO po = new DiagramConversationMessagePO();
        po.setUserId(message.getUserId());
        po.setDiagramId(message.getDiagramId());
        po.setSessionId(message.getSessionId());
        po.setClientMessageId(message.getClientMessageId());
        po.setRole(message.getRole());
        po.setContent(message.getContent());
        return po;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
