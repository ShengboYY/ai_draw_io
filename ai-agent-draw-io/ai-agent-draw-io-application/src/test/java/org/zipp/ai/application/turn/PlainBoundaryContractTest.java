package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the M2 source-free seam against accidental source-aware dependency injection. */
class PlainBoundaryContractTest {

    private static final Set<String> SOURCE_AWARE_TOKENS = Set.of(
            "Source", "Snapshot", "Material", "Rag", "Evidence", "Citation", "Retrieval");

    @Test
    void plainHandlersExposeOnlySourceFreePortsAndState() {
        assertNoSourceAwareTypes(PlainDrawingHandler.class);
        assertNoSourceAwareTypes(PlainResponseHandler.class);
    }

    @Test
    void plainRuntimeIsFixedToGenerationOnly() {
        PlainRuntimeRegistry registry = new PlainRuntimeRegistry();

        assertTrue(registry.isSourceFree());
        assertTrue(registry.capabilities().contains(PlainRuntimeCapability.PLAIN_GENERATION));
        assertFalse(registry.capabilities().stream()
                .map(Enum::name)
                .anyMatch(name -> name.contains("SOURCE") || name.contains("MEMORY")));
    }

    private void assertNoSourceAwareTypes(Class<?> handler) {
        List<Class<?>> constructorTypes = Arrays.stream(handler.getDeclaredConstructors())
                .map(Constructor::getParameterTypes)
                .flatMap(Arrays::stream)
                .toList();
        List<Class<?>> fieldTypes = Arrays.stream(handler.getDeclaredFields())
                .map(Field::getType)
                .toList();

        assertFalse(anyTypeContains(constructorTypes),
                () -> handler.getSimpleName() + " constructor contains source-aware type");
        assertFalse(anyTypeContains(fieldTypes),
                () -> handler.getSimpleName() + " field contains source-aware type");
    }

    private boolean anyTypeContains(List<Class<?>> types) {
        return types.stream().anyMatch(type -> SOURCE_AWARE_TOKENS.stream()
                .anyMatch(token -> type.getSimpleName().contains(token)));
    }
}
