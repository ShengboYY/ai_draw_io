package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.aggregate.Material;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.infrastructure.dao.material.IMaterialLifecycleMapper;
import org.zipp.ai.infrastructure.dao.material.po.MaterialPO;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MySqlMaterialLifecycleAdapterTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final CatalogOwner USER = new CatalogOwner(OwnerType.USER, "user_1");

    @Test
    void promotionPersistsScopeAndRetentionInOneFencedMutation() {
        Material material = temporary();
        material.retain(new MaterialScopeLink(MaterialScopeType.LIBRARY, "personal", "user_1"), NOW);
        MaterialScopeReference scope = new MaterialScopeReference("scope_1",
                MaterialScopeType.LIBRARY, "personal");
        MaterialLifecycleMutation mutation = mutation(material, MaterialLifecycleAction.PROMOTE,
                scope, null);
        AtomicReference<MaterialPO> updated = new AtomicReference<>();
        AtomicReference<String> insertedScope = new AtomicReference<>();
        IMaterialLifecycleMapper mapper = mapper((method, args) -> switch (method) {
            case "selectLifecycleRequest" -> null;
            case "lockOwnedLifecycle" -> po("ACTIVE", "TEMPORARY", 0);
            case "insertLifecycleScope" -> {
                insertedScope.set((String) args[4]);
                yield 1;
            }
            case "updateLifecycle" -> {
                updated.set((MaterialPO) args[0]);
                yield 1;
            }
            case "insertLifecycleRequest" -> 1;
            default -> unsupported(method);
        });

        MaterialLifecycleResult result = new MySqlMaterialLifecycleAdapter(mapper).apply(mutation);

        assertEquals("LIBRARY", insertedScope.get());
        assertEquals("RETAINED", updated.get().getRetentionClass());
        assertNull(updated.get().getExpiresAt());
        assertEquals(MaterialLifecycleState.ACTIVE, result.lifecycleState());
    }

    @Test
    void anonymousRemovalFencesJobsAndCreatesOneDurableDeletionTask() {
        CatalogOwner owner = new CatalogOwner(OwnerType.ANONYMOUS, "anon_1");
        Material material = Material.createTemporary("material_1", OwnerType.ANONYMOUS,
                "anon_1", MaterialKind.PDF, "Guide", "conversation_1", NOW.minusSeconds(60));
        material.remove(NOW);
        MaterialLifecycleMutation mutation = new MaterialLifecycleMutation(owner,
                MaterialLifecycleAction.REMOVE, "a".repeat(64), 0, material, null,
                "del_1", NOW);
        AtomicReference<String> deletionTask = new AtomicReference<>();
        IMaterialLifecycleMapper mapper = mapper((method, args) -> switch (method) {
            case "selectLifecycleRequest" -> null;
            case "lockOwnedLifecycle" -> po("ACTIVE", "TEMPORARY", 0);
            case "updateLifecycle", "insertLifecycleRequest", "cancelQueuedMaterialJobs" -> 1;
            case "insertDeletionTask" -> {
                deletionTask.set((String) args[0]);
                yield 1;
            }
            default -> unsupported(method);
        });

        MaterialLifecycleResult result = new MySqlMaterialLifecycleAdapter(mapper).apply(mutation);

        assertEquals(MaterialLifecycleState.DELETE_PENDING, result.lifecycleState());
        assertEquals("del_1", deletionTask.get());
    }

    private MaterialLifecycleMutation mutation(Material material, MaterialLifecycleAction action,
                                                MaterialScopeReference scope, String deletionTaskId) {
        return new MaterialLifecycleMutation(USER, action, "a".repeat(64), 0, material,
                scope, deletionTaskId, NOW);
    }

    private Material temporary() {
        return Material.createTemporary("material_1", OwnerType.USER, "user_1", MaterialKind.PDF,
                "Guide", "conversation_1", NOW.minusSeconds(60));
    }

    private MaterialPO po(String state, String retention, long generation) {
        MaterialPO po = new MaterialPO();
        po.setId("material_1");
        po.setOwnerType("USER");
        po.setOwnerKey("user_1");
        po.setKind("PDF");
        po.setDisplayName("Guide");
        po.setRetentionClass(retention);
        po.setOriginConversationId("conversation_1");
        po.setLifecycleState(state);
        po.setLifecycleGeneration(generation);
        po.setLastMeaningfulActivityAt(NOW.minusSeconds(60));
        po.setExpiresAt(NOW.plusSeconds(24 * 3600));
        return po;
    }

    @SuppressWarnings("unchecked")
    private IMaterialLifecycleMapper mapper(Call call) {
        return (IMaterialLifecycleMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IMaterialLifecycleMapper.class},
                (proxy, method, args) -> call.invoke(method.getName(),
                        args == null ? new Object[0] : args));
    }

    private static Object unsupported(String method) {
        throw new AssertionError("unexpected mapper call: " + method);
    }

    @FunctionalInterface
    private interface Call {
        Object invoke(String method, Object[] args);
    }
}
