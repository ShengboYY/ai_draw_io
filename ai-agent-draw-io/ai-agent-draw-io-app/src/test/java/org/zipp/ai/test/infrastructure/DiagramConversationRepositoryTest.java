package org.zipp.ai.test.infrastructure;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.conversation.DiagramConversationMessage;
import org.zipp.ai.infrastructure.adapter.repository.DiagramConversationRepository;
import org.zipp.ai.infrastructure.dao.IDiagramConversationMapper;
import org.zipp.ai.infrastructure.dao.po.DiagramConversationMessagePO;
import org.zipp.ai.infrastructure.dao.po.ConversationMessageAttachmentPO;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagramConversationRepositoryTest {

    @Test
    public void shouldSaveDiagramConversationMessages() throws Exception {
        DiagramConversationRepository repository = new DiagramConversationRepository();
        FakeDiagramConversationMapper mapper = new FakeDiagramConversationMapper();
        injectMapper(repository, mapper);

        repository.saveMessages(List.of(DiagramConversationMessage.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .sessionId("session-1")
                .clientMessageId("msg-1")
                .role("user")
                .content("Create a flowchart")
                .build()));

        assertEquals(1, mapper.saved.size());
        assertEquals("alice", mapper.saved.get(0).getUserId());
        assertEquals("diagram-1", mapper.saved.get(0).getDiagramId());
        assertEquals("msg-1", mapper.saved.get(0).getClientMessageId());
        assertEquals("user", mapper.saved.get(0).getRole());
    }

    @Test
    public void shouldListDiagramConversationMessages() throws Exception {
        DiagramConversationRepository repository = new DiagramConversationRepository();
        FakeDiagramConversationMapper mapper = new FakeDiagramConversationMapper();
        injectMapper(repository, mapper);

        List<DiagramConversationMessage> messages = repository.listMessages("alice", "diagram-1");

        assertEquals("alice", mapper.listedUserId);
        assertEquals("diagram-1", mapper.listedDiagramId);
        assertEquals(2, messages.size());
        assertEquals("msg-1", messages.get(0).getClientMessageId());
        assertEquals("agent", messages.get(1).getRole());
        assertEquals(List.of("architecture.pdf"), messages.get(0).getAttachmentRefs());
        assertTrue(messages.get(1).getAttachmentRefs().isEmpty());
    }

    @Test
    public void shouldSkipInvalidMessagesWhenSaving() throws Exception {
        DiagramConversationRepository repository = new DiagramConversationRepository();
        FakeDiagramConversationMapper mapper = new FakeDiagramConversationMapper();
        injectMapper(repository, mapper);

        repository.saveMessages(List.of(
                DiagramConversationMessage.builder().userId(" ").diagramId("diagram-1").clientMessageId("bad").role("user").content("x").build(),
                DiagramConversationMessage.builder().userId("alice").diagramId("diagram-1").clientMessageId("msg-1").role("agent").content("ok").build()
        ));

        assertEquals(1, mapper.saved.size());
        assertEquals("msg-1", mapper.saved.get(0).getClientMessageId());
    }

    @Test
    public void shouldReturnEmptyListForBlankLookup() throws Exception {
        DiagramConversationRepository repository = new DiagramConversationRepository();
        FakeDiagramConversationMapper mapper = new FakeDiagramConversationMapper();
        injectMapper(repository, mapper);

        List<DiagramConversationMessage> messages = repository.listMessages("alice", " ");

        assertTrue(messages.isEmpty());
        assertFalse(mapper.listCalled);
    }

    private void injectMapper(DiagramConversationRepository repository, IDiagramConversationMapper mapper) throws Exception {
        Field field = DiagramConversationRepository.class.getDeclaredField("diagramConversationMapper");
        field.setAccessible(true);
        field.set(repository, mapper);
    }

    private static class FakeDiagramConversationMapper implements IDiagramConversationMapper {

        private final List<DiagramConversationMessagePO> saved = new java.util.ArrayList<>();
        private boolean listCalled;
        private String listedUserId;
        private String listedDiagramId;

        @Override
        public int upsertMessage(DiagramConversationMessagePO message) {
            saved.add(message);
            return 1;
        }

        @Override
        public List<DiagramConversationMessagePO> selectMessages(String userId, String diagramId) {
            this.listCalled = true;
            this.listedUserId = userId;
            this.listedDiagramId = diagramId;

            DiagramConversationMessagePO first = new DiagramConversationMessagePO();
            first.setId(101L);
            first.setUserId(userId);
            first.setDiagramId(diagramId);
            first.setSessionId("session-1");
            first.setClientMessageId("msg-1");
            first.setRole("user");
            first.setContent("Create a flowchart");

            DiagramConversationMessagePO second = new DiagramConversationMessagePO();
            second.setId(102L);
            second.setUserId(userId);
            second.setDiagramId(diagramId);
            second.setSessionId("session-1");
            second.setTurnId("turn-1");
            second.setClientMessageId("msg-2");
            second.setRole("agent");
            second.setContent("Done");

            return List.of(first, second);
        }

        @Override
        public List<DiagramConversationMessagePO> selectMessagesByScope(
                String userId,
                String diagramId,
                List<String> scopeKeys
        ) {
            // This fake does not model scope filtering; repository mapping is identical.
            return selectMessages(userId, diagramId);
        }

        @Override
        public DiagramConversationMessagePO selectAssistantMessageByTurn(
                String userId, String diagramId, String conversationId, String turnId) {
            return null;
        }

        @Override
        public List<ConversationMessageAttachmentPO> selectAttachmentsByMessages(
                String userId, String diagramId, List<Long> messageIds) {
            ConversationMessageAttachmentPO attachment = new ConversationMessageAttachmentPO();
            attachment.setMessageId(101L);
            attachment.setFileRef("upload-1");
            attachment.setDisplayName("architecture.pdf");
            return List.of(attachment);
        }

        @Override
        public int deleteByUserId(String userId) {
            return 1;
        }
    }
}
