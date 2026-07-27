package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.ConversationCatalogPort;
import org.zipp.ai.application.turn.ConversationRef;
import org.zipp.ai.application.turn.ConversationStatus;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;
import org.zipp.ai.infrastructure.dao.material.IUploadSessionMapper;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlUploadScopeAuthorizerTest {

    @Test
    void unresolvedRuntimeSessionIsProvisionedAndCanonicalized() {
        MySqlConversationScopeKeyResolver resolver = new MySqlConversationScopeKeyResolver(
                new StubJdbc(List.of(List.of(), List.of())).operations());
        FakeConversationCatalog catalog = new FakeConversationCatalog();
        MySqlUploadScopeAuthorizer authorizer = new MySqlUploadScopeAuthorizer(
                mapper(), resolver, catalog);

        UploadTarget canonical = authorizer.canonicalTarget(
                OwnerType.USER,
                "owner-1",
                new UploadTarget(MaterialScopeType.CONVERSATION, "runtime-session-1", RetentionClass.TEMPORARY),
                "diagram-1");

        assertEquals("conv-1", canonical.scopeKey());
        assertEquals("runtime-session-1", catalog.boundLegacySessionId);
        assertEquals("diagram-1", catalog.boundDiagramId);
    }

    @Test
    void unresolvedCanonicalLookingIdRemainsFailClosed() {
        MySqlConversationScopeKeyResolver resolver = new MySqlConversationScopeKeyResolver(
                new StubJdbc(List.of(List.of(), List.of())).operations());
        FakeConversationCatalog catalog = new FakeConversationCatalog();
        MySqlUploadScopeAuthorizer authorizer = new MySqlUploadScopeAuthorizer(
                mapper(), resolver, catalog);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> authorizer.canonicalTarget(
                        OwnerType.USER,
                        "owner-1",
                        new UploadTarget(MaterialScopeType.CONVERSATION, "conv_stale", RetentionClass.TEMPORARY),
                        "diagram-1"));

        assertEquals("CONVERSATION_SCOPE_REFERENCE_UNRESOLVED", failure.getMessage());
        assertNull(catalog.boundLegacySessionId);
    }

    @Test
    void canonicalConversationEstablishesTheSourceContextOnFirstUpload() {
        boolean[] bound = {false};
        String[] boundConversationId = {null};
        IUploadSessionMapper mapper = (IUploadSessionMapper) Proxy.newProxyInstance(
                IUploadSessionMapper.class.getClassLoader(),
                new Class<?>[]{IUploadSessionMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "countOwnedConversation" -> bound[0] ? 1 : 0;
                    case "countOwnedDiagram" -> 1;
                    case "bindConversation" -> {
                        bound[0] = true;
                        boundConversationId[0] = (String) args[0];
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        MySqlUploadScopeAuthorizer authorizer = new MySqlUploadScopeAuthorizer(mapper);

        boolean allowed = authorizer.canUpload(
                OwnerType.USER,
                "owner-1",
                new UploadTarget(MaterialScopeType.CONVERSATION, "conv-1", RetentionClass.TEMPORARY),
                "diagram-1",
                null);

        assertTrue(allowed);
        assertEquals("conv-1", boundConversationId[0]);
    }

    private static IUploadSessionMapper mapper() {
        return (IUploadSessionMapper) Proxy.newProxyInstance(
                IUploadSessionMapper.class.getClassLoader(),
                new Class<?>[]{IUploadSessionMapper.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    private static final class FakeConversationCatalog implements ConversationCatalogPort {
        private String boundLegacySessionId;
        private String boundDiagramId;

        @Override
        public ConversationRef findOrCreateDefault(AuthenticatedActor actor, String diagramId) {
            return conversation(actor, diagramId);
        }

        @Override
        public ConversationRef findOrCreateDefaultForLegacySession(
                AuthenticatedActor actor,
                String legacySessionId,
                String diagramId
        ) {
            boundLegacySessionId = legacySessionId;
            boundDiagramId = diagramId;
            return conversation(actor, diagramId);
        }

        @Override
        public ConversationRef requireActiveBinding(
                AuthenticatedActor actor,
                String conversationId,
                String diagramId
        ) {
            return conversation(actor, diagramId);
        }

        @Override
        public ConversationRef resolveLegacyAlias(
                AuthenticatedActor actor,
                String legacySessionId,
                String diagramId
        ) {
            return conversation(actor, diagramId);
        }

        private ConversationRef conversation(AuthenticatedActor actor, String diagramId) {
            return new ConversationRef("conv-1", actor.ownerKey(), diagramId, ConversationStatus.ACTIVE);
        }
    }

    private static final class StubJdbc {
        private final Queue<List<?>> responses;

        private StubJdbc(List<List<?>> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        private JdbcOperations operations() {
            return (JdbcOperations) Proxy.newProxyInstance(
                    JdbcOperations.class.getClassLoader(),
                    new Class<?>[]{JdbcOperations.class},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("query")) {
                            throw new UnsupportedOperationException(method.getName());
                        }
                        if (responses.isEmpty()) {
                            throw new AssertionError("unexpected query");
                        }
                        return responses.remove();
                    });
        }
    }
}
