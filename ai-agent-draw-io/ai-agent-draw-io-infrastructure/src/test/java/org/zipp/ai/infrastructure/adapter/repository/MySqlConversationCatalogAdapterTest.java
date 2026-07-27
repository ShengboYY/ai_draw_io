package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.ConversationRef;
import org.zipp.ai.application.turn.ConversationStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MySqlConversationCatalogAdapterTest {

    private static final AuthenticatedActor ACTOR = new AuthenticatedActor("owner-1", "owner-1");
    private static final ConversationRef CONVERSATION = new ConversationRef(
            "conv-1", "owner-1", "diagram-1", ConversationStatus.ACTIVE);

    @Test
    void freshRuntimeSessionIsBoundToTheOwnedDiagramDefaultConversation() {
        StubJdbc jdbc = new StubJdbc(List.of(
                List.of("diagram-1"),
                List.of(CONVERSATION),
                List.of(),
                List.of(0),
                List.of("conv-1")));
        MySqlConversationCatalogAdapter catalog = new MySqlConversationCatalogAdapter(jdbc.operations());

        ConversationRef resolved = catalog.findOrCreateDefaultForLegacySession(
                ACTOR, "runtime-session-1", "diagram-1");

        assertEquals(CONVERSATION, resolved);
        assertEquals(1, jdbc.updates().size());
        assertEquals(List.of("owner-1", "conv-1", "runtime-session-1"), jdbc.updates().get(0).args());
    }

    @Test
    void existingAliasCannotBeReboundToAnotherConversation() {
        StubJdbc jdbc = new StubJdbc(List.of(
                List.of("diagram-1"),
                List.of(CONVERSATION),
                List.of("conv-other")));
        MySqlConversationCatalogAdapter catalog = new MySqlConversationCatalogAdapter(jdbc.operations());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> catalog.findOrCreateDefaultForLegacySession(
                        ACTOR, "runtime-session-1", "diagram-1"));

        assertEquals("LEGACY_CONVERSATION_ALIAS_CONFLICT", failure.getMessage());
        assertEquals(0, jdbc.updates().size());
    }

    @Test
    void aliasBoundIsEnforcedBeforeInsertion() {
        StubJdbc jdbc = new StubJdbc(List.of(
                List.of("diagram-1"),
                List.of(CONVERSATION),
                List.of(),
                List.of(8)));
        MySqlConversationCatalogAdapter catalog = new MySqlConversationCatalogAdapter(jdbc.operations());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> catalog.findOrCreateDefaultForLegacySession(
                        ACTOR, "runtime-session-9", "diagram-1"));

        assertEquals("CONVERSATION_SCOPE_ALIAS_LIMIT_EXCEEDED", failure.getMessage());
        assertEquals(0, jdbc.updates().size());
    }

    private record UpdateCall(String sql, List<Object> args) {
    }

    private static final class StubJdbc {
        private final Queue<List<?>> responses;
        private final List<UpdateCall> updates = new ArrayList<>();

        private StubJdbc(List<List<?>> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        private JdbcOperations operations() {
            return (JdbcOperations) Proxy.newProxyInstance(
                    JdbcOperations.class.getClassLoader(),
                    new Class<?>[]{JdbcOperations.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("query")) {
                            if (responses.isEmpty()) {
                                throw new AssertionError("unexpected query: " + args[0]);
                            }
                            return responses.remove();
                        }
                        if (method.getName().equals("update")) {
                            Object[] parameters = (Object[]) args[1];
                            updates.add(new UpdateCall((String) args[0], List.of(parameters)));
                            return 1;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        private List<UpdateCall> updates() {
            return List.copyOf(updates);
        }
    }
}
