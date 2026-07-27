package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.domain.agent.model.valobj.conversation.DiagramConversationMessage;
import org.zipp.ai.domain.agent.service.IDiagramConversationStore;
import org.zipp.ai.infrastructure.dao.IDiagramConversationMapper;
import org.zipp.ai.infrastructure.dao.po.ConversationMessageAttachmentPO;
import org.zipp.ai.infrastructure.dao.po.DiagramConversationMessagePO;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class DiagramConversationRepository implements IDiagramConversationStore {

    @Resource
    private IDiagramConversationMapper diagramConversationMapper;

    @Resource
    private MySqlConversationScopeKeyResolver conversationScopes;

    @Override
    public List<DiagramConversationMessage> listMessages(String userId, String diagramId) {
        if (isBlank(userId) || isBlank(diagramId)) {
            return Collections.emptyList();
        }
        // The history endpoint has no session parameter, so it must include completed V2 turns
        // written under a legacy session's canonical conversation as well as default-scope messages.
        return toMessagesWithAttachments(
                diagramConversationMapper.selectMessages(userId, diagramId), userId, diagramId);
    }

    @Override
    public List<DiagramConversationMessage> listMessages(
            String userId, String diagramId, String conversationReference) {
        if (isBlank(userId) || isBlank(diagramId)) {
            return Collections.emptyList();
        }
        List<DiagramConversationMessagePO> rows;
        if (conversationScopes == null) {
            rows = diagramConversationMapper.selectMessages(userId, diagramId);
        } else {
            List<String> keys = conversationScopes.readableScopeKeys(
                    new AuthenticatedActor(userId, userId), conversationReference, diagramId).allKeys();
            rows = diagramConversationMapper.selectMessagesByScope(userId, diagramId, keys);
        }
        return toMessagesWithAttachments(rows, userId, diagramId);
    }

    private List<DiagramConversationMessage> toMessagesWithAttachments(
            List<DiagramConversationMessagePO> rows, String userId, String diagramId) {
        List<DiagramConversationMessage> messages = rows.stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
        List<Long> messageIds = messages.stream().map(DiagramConversationMessage::getId)
                .filter(Objects::nonNull).distinct().toList();
        if (messageIds.isEmpty()) {
            return messages;
        }
        Map<Long, List<String>> attachmentsByMessage =
                diagramConversationMapper.selectAttachmentsByMessages(
                        userId, diagramId, messageIds).stream().collect(Collectors.groupingBy(
                        ConversationMessageAttachmentPO::getMessageId,
                        Collectors.mapping(ConversationMessageAttachmentPO::getDisplayName, Collectors.toList())));
        messages.forEach(message -> message.setAttachmentRefs(
                attachmentsByMessage.getOrDefault(message.getId(), List.of())));
        return messages;
    }

    @Override
    public Optional<DiagramConversationMessage> findAssistantMessage(
            String userId, String diagramId, String canonicalConversationId, String turnId) {
        if (isBlank(userId) || isBlank(diagramId) || isBlank(canonicalConversationId) || isBlank(turnId)) {
            return Optional.empty();
        }
        // Admission already resolved this immutable scope; never resolve a legacy alias a second time.
        return Optional.ofNullable(diagramConversationMapper.selectAssistantMessageByTurn(
                userId, diagramId, canonicalConversationId, turnId)).map(this::toDomain);
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
                .forEach(message -> {
                    DiagramConversationMessagePO stored = toPo(message);
                    diagramConversationMapper.upsertMessage(stored);
                    persistLegacyAttachments(stored, message.getAttachmentRefs());
                });
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
                .id(po.getId())
                .userId(po.getUserId())
                .diagramId(po.getDiagramId())
                .turnId(po.getTurnId())
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
        po.setTurnId(message.getTurnId());
        po.setSessionId(message.getSessionId());
        if (conversationScopes != null) {
            // Messages without a legacy session still belong to the diagram's durable default conversation.
            String conversationReference = isBlank(message.getSessionId()) ? "default" : message.getSessionId();
            po.setConversationId(conversationScopes.newWriteScopeKey(
                    new AuthenticatedActor(message.getUserId(), message.getUserId()),
                    conversationReference, message.getDiagramId()));
        }
        po.setClientMessageId(message.getClientMessageId());
        po.setRole(message.getRole());
        po.setContent(message.getContent());
        return po;
    }

    private void persistLegacyAttachments(
            DiagramConversationMessagePO message,
            List<String> attachmentRefs
    ) {
        if (message.getId() == null
                || !"user".equalsIgnoreCase(message.getRole())
                || attachmentRefs == null
                || attachmentRefs.isEmpty()) {
            return;
        }
        List<String> normalizedRefs = attachmentRefs.stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .map(String::trim)
                .distinct()
                .limit(16)
                .toList();
        for (int index = 0; index < normalizedRefs.size(); index++) {
            // The mapper's INSERT ... SELECT is the authorization boundary; an invalid ref writes zero rows.
            diagramConversationMapper.insertMessageAttachment(
                    message.getUserId(),
                    message.getDiagramId(),
                    message.getId(),
                    index,
                    normalizedRefs.get(index));
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
