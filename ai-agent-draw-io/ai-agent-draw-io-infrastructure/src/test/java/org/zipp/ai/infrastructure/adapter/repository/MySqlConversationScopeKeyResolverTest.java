package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.ConversationRef;
import org.zipp.ai.application.turn.ConversationScopeKeys;
import org.zipp.ai.application.turn.ConversationStatus;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MySqlConversationScopeKeyResolverTest {

    private static final AuthenticatedActor ACTOR = new AuthenticatedActor("owner-1", "owner-1");
    private static final ConversationRef CONVERSATION = new ConversationRef(
            "conv-1", "owner-1", "diagram-1", ConversationStatus.ACTIVE);

    @Test
    void canonicalReadReturnsTheCompleteBoundedAliasSet() {
        StubJdbc jdbc = new StubJdbc(List.of(List.of("legacy-b", "legacy-a")));
        MySqlConversationScopeKeyResolver resolver = new MySqlConversationScopeKeyResolver(jdbc.operations());

        ConversationScopeKeys keys = resolver.readableScopeKeys(ACTOR, CONVERSATION);

        assertEquals(List.of("conv-1", "legacy-b", "legacy-a"), keys.allKeys());
        assertEquals("conv-1", resolver.newWriteScopeKey(CONVERSATION));
    }

    @Test
    void legacyReadResolvesToCanonicalThenLoadsAliases() {
        StubJdbc jdbc = new StubJdbc(List.of(
                List.of(),
                List.of(CONVERSATION),
                List.of("legacy-old")));
        MySqlConversationScopeKeyResolver resolver = new MySqlConversationScopeKeyResolver(jdbc.operations());

        assertEquals(List.of("conv-1", "legacy-old"),
                resolver.readableScopeKeys(ACTOR, "legacy-old", "diagram-1").allKeys());
    }

    @Test
    void overBoundedAliasSetFailsClosedInsteadOfTruncating() {
        StubJdbc jdbc = new StubJdbc(List.of(List.of(
                "a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9")));
        MySqlConversationScopeKeyResolver resolver = new MySqlConversationScopeKeyResolver(jdbc.operations());

        assertThrows(IllegalStateException.class, () -> resolver.readableScopeKeys(ACTOR, CONVERSATION));
    }

    @Test
    void anotherOwnerCannotUseTheConversationBinding() {
        MySqlConversationScopeKeyResolver resolver = new MySqlConversationScopeKeyResolver(
                new StubJdbc(List.of(List.of("legacy"))).operations());

        assertThrows(IllegalStateException.class, () -> resolver.readableScopeKeys(
                new AuthenticatedActor("owner-2", "owner-2"), CONVERSATION));
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
