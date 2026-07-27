package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.infrastructure.dao.material.IMaterialCatalogMapper;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MySqlMaterialCatalogAdapterTest {
    private static final CatalogOwner OWNER = new CatalogOwner(OwnerType.USER, "user_1");

    @Test
    void scopeTargetsAreCheckedAgainstTheAuthoritativeOwner() {
        IMaterialCatalogMapper mapper = proxy((method, args) -> switch (method) {
            case "countOwnedDiagram", "countOwnedActiveChartbook" -> "user_1".equals(args[0]) ? 1 : 0;
            default -> unsupported(method);
        });
        MySqlMaterialCatalogAdapter adapter = new MySqlMaterialCatalogAdapter(mapper);

        assertTrue(adapter.scopeTargetOwned(OWNER, MaterialScopeType.LIBRARY, "user_1"));
        assertTrue(adapter.scopeTargetOwned(OWNER, MaterialScopeType.LIBRARY, "personal"));
        assertTrue(adapter.scopeTargetOwned(OWNER, MaterialScopeType.LIBRARY, "library"));
        assertFalse(adapter.scopeTargetOwned(OWNER, MaterialScopeType.LIBRARY, "user_2"));
        assertTrue(adapter.scopeTargetOwned(OWNER, MaterialScopeType.DIAGRAM, "diagram_1"));
        assertTrue(adapter.scopeTargetOwned(OWNER, MaterialScopeType.CHARTBOOK, "book_1"));
        // Conversation ownership is enforced by the material owner tuple plus its exact scope link.
        assertTrue(adapter.scopeTargetOwned(OWNER, MaterialScopeType.CONVERSATION, "conversation_1"));
    }

    @Test
    void removingScopeLocksTheMaterialAndPreservesTheFinalDurableLink() {
        List<String> calls = new ArrayList<>();
        IMaterialCatalogMapper mapper = proxy((method, args) -> {
            calls.add(method);
            return switch (method) {
                case "lockOwnedActiveMaterial" -> "material_1";
                case "countOwnedScopes" -> 2;
                case "deleteOwnedScope" -> 1;
                case "restoreTemporaryWhenConversationOnly" -> 1;
                default -> unsupported(method);
            };
        });

        assertTrue(new MySqlMaterialCatalogAdapter(mapper)
                .removeScope(OWNER, "material_1", "scope_2"));
        assertTrue(calls.indexOf("lockOwnedActiveMaterial") < calls.indexOf("countOwnedScopes"));
        assertTrue(calls.indexOf("countOwnedScopes") < calls.indexOf("deleteOwnedScope"));
        assertTrue(calls.indexOf("deleteOwnedScope")
                < calls.indexOf("restoreTemporaryWhenConversationOnly"));
    }

    @Test
    void finalScopeIsNotDeletedEvenIfTheCallerUsedAStaleRead() {
        IMaterialCatalogMapper mapper = proxy((method, args) -> switch (method) {
            case "lockOwnedActiveMaterial" -> "material_1";
            case "countOwnedScopes" -> 1;
            default -> unsupported(method);
        });

        assertFalse(new MySqlMaterialCatalogAdapter(mapper)
                .removeScope(OWNER, "material_1", "scope_1"));
    }

    @Test
    void finalScopeAndActiveLifecycleAreRemovedInOneTransaction() {
        List<String> calls = new ArrayList<>();
        IMaterialCatalogMapper mapper = proxy((method, args) -> {
            calls.add(method);
            return switch (method) {
                case "lockOwnedActiveMaterial" -> "material_1";
                case "countOwnedScopes", "deleteOwnedScope", "trashOwnedMaterial" -> 1;
                default -> unsupported(method);
            };
        });

        assertTrue(new MySqlMaterialCatalogAdapter(mapper)
                .removeLastScopeAndTrash(OWNER, "material_1", "scope_1"));
        assertTrue(calls.indexOf("deleteOwnedScope") < calls.indexOf("trashOwnedMaterial"));
    }

    @Test
    void failedFinalLifecycleTransitionDoesNotReportTheDeletedScopeAsCommitted() {
        IMaterialCatalogMapper mapper = proxy((method, args) -> switch (method) {
            case "lockOwnedActiveMaterial" -> "material_1";
            case "countOwnedScopes", "deleteOwnedScope" -> 1;
            case "trashOwnedMaterial" -> 0;
            default -> unsupported(method);
        });

        assertThrows(IllegalStateException.class, () -> new MySqlMaterialCatalogAdapter(mapper)
                .removeLastScopeAndTrash(OWNER, "material_1", "scope_1"));
    }

    private static Object unsupported(String method) {
        throw new UnsupportedOperationException(method);
    }

    @SuppressWarnings("unchecked")
    private static IMaterialCatalogMapper proxy(Call call) {
        return (IMaterialCatalogMapper) Proxy.newProxyInstance(
                IMaterialCatalogMapper.class.getClassLoader(), new Class<?>[]{IMaterialCatalogMapper.class},
                (proxy, method, args) -> call.invoke(method.getName(), args == null ? new Object[0] : args));
    }

    @FunctionalInterface
    private interface Call {
        Object invoke(String method, Object[] args);
    }
}
