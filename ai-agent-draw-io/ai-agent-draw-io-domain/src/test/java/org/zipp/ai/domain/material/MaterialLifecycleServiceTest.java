package org.zipp.ai.domain.material;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.aggregate.Material;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialLifecyclePort;
import org.zipp.ai.domain.material.service.MaterialLifecycleService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MaterialLifecycleServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final CatalogOwner USER = new CatalogOwner(OwnerType.USER, "user_1");

    @Test
    void temporaryMaterialCanBePromotedWithoutCopyingItsContent() {
        FakeLifecyclePort port = new FakeLifecyclePort(temporary(USER));
        MaterialLifecycleService service = service(port);

        MaterialLifecycleResult result = service.promote(USER, "material_1",
                MaterialScopeType.LIBRARY, "personal", "promote-1");

        assertEquals(RetentionClass.RETAINED, result.retentionClass());
        assertNull(result.expiresAt());
        assertEquals(MaterialScopeType.LIBRARY, port.mutation.scopeLink().scopeType());
        assertEquals(1, port.mutation.material().lifecycleGeneration());
    }

    @Test
    void removeRoutesRegisteredMaterialToTrashAndAnonymousMaterialToDeletion() {
        FakeLifecyclePort registered = new FakeLifecyclePort(temporary(USER));
        MaterialLifecycleResult trashed = service(registered).remove(USER, "material_1", "remove-1");

        CatalogOwner anonymousOwner = new CatalogOwner(OwnerType.ANONYMOUS, "anon_1");
        FakeLifecyclePort anonymous = new FakeLifecyclePort(temporary(anonymousOwner));
        MaterialLifecycleResult pending = service(anonymous).remove(
                anonymousOwner, "material_1", "remove-2");

        assertEquals(MaterialLifecycleState.TRASHED, trashed.lifecycleState());
        assertEquals(NOW.plusSeconds(30L * 24 * 3600), trashed.trashExpiresAt());
        assertEquals(MaterialLifecycleState.DELETE_PENDING, pending.lifecycleState());
        assertTrue(anonymous.deletionTaskRequested);
    }

    @Test
    void restoredTemporaryMaterialKeepsItsConversationAndGetsANewTtl() {
        Material material = temporary(USER);
        material.remove(NOW.minusSeconds(60));
        FakeLifecyclePort port = new FakeLifecyclePort(material);

        MaterialLifecycleResult restored = service(port).restore(
                USER, "material_1", "restore-1");

        assertEquals(MaterialLifecycleState.ACTIVE, restored.lifecycleState());
        assertEquals(NOW.plusSeconds(24 * 3600), restored.expiresAt());
        assertEquals("conversation_1", port.mutation.material().originConversationId());
    }

    @Test
    void expiredMaterialCannotReceiveMeaningfulActivity() {
        Material expired = Material.rehydrateTemporaryActive("material_1", OwnerType.USER,
                "user_1", MaterialKind.PDF, "Guide", "conversation_1", 0,
                NOW.minusSeconds(25 * 3600), NOW.minusSeconds(3600));
        FakeLifecyclePort port = new FakeLifecyclePort(expired);

        assertThrows(CatalogOperationException.class,
                () -> service(port).recordMeaningfulActivity(USER, "material_1"));
        assertNull(port.mutation);
    }

    @Test
    void ttlExpiryUsesTrashForUsersAndPermanentDeletionForAnonymousOwners() {
        Material userMaterial = Material.rehydrateTemporaryActive("material_1", OwnerType.USER,
                "user_1", MaterialKind.PDF, "Guide", "conversation_1", 0,
                NOW.minusSeconds(3600), NOW);
        FakeLifecyclePort userPort = new FakeLifecyclePort(userMaterial);
        userPort.expiredTemporary = List.of(new MaterialExpiryCandidate(USER, "material_1", 0, NOW));

        CatalogOwner anonymousOwner = new CatalogOwner(OwnerType.ANONYMOUS, "anon_1");
        Material anonymousMaterial = Material.rehydrateTemporaryActive("material_1", OwnerType.ANONYMOUS,
                "anon_1", MaterialKind.PDF, "Guide", "conversation_1", 0,
                NOW.minusSeconds(3600), NOW);
        FakeLifecyclePort anonymousPort = new FakeLifecyclePort(anonymousMaterial);
        anonymousPort.expiredTemporary = List.of(
                new MaterialExpiryCandidate(anonymousOwner, "material_1", 0, NOW));

        assertEquals(1, service(userPort).expireTemporary(100));
        assertEquals(MaterialLifecycleState.TRASHED, userPort.material.lifecycleState());
        assertEquals(1, service(anonymousPort).expireTemporary(100));
        assertEquals(MaterialLifecycleState.DELETE_PENDING, anonymousPort.material.lifecycleState());
        assertTrue(anonymousPort.deletionTaskRequested);
    }

    @Test
    void repeatedLifecycleCommandReturnsItsFirstDurableResult() {
        FakeLifecyclePort port = new FakeLifecyclePort(temporary(USER));
        MaterialLifecycleService service = service(port);

        MaterialLifecycleResult first = service.remove(USER, "material_1", "remove-once");
        MaterialLifecycleResult repeated = service.remove(USER, "material_1", "remove-once");

        assertEquals(first, repeated);
        assertEquals(1, port.applyCount);
    }

    private MaterialLifecycleService service(FakeLifecyclePort port) {
        return new MaterialLifecycleService(port, prefix -> prefix + "_1",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Material temporary(CatalogOwner owner) {
        return Material.createTemporary("material_1", owner.ownerType(), owner.ownerKey(),
                MaterialKind.PDF, "Guide", "conversation_1", NOW.minusSeconds(3600));
    }

    private static final class FakeLifecyclePort implements MaterialLifecyclePort {
        private Material material;
        private MaterialLifecycleMutation mutation;
        private boolean deletionTaskRequested;
        private int applyCount;
        private List<MaterialExpiryCandidate> expiredTemporary = List.of();
        private final Map<String, MaterialLifecycleResult> applied = new HashMap<>();

        private FakeLifecyclePort(Material material) {
            this.material = material;
        }

        @Override
        public Optional<Material> findOwned(CatalogOwner owner, String materialId) {
            return Optional.ofNullable(material);
        }

        @Override
        public Optional<MaterialLifecycleResult> findApplied(CatalogOwner owner, String materialId,
                                                             MaterialLifecycleAction action,
                                                             String requestFingerprint) {
            return Optional.ofNullable(applied.get(action.name() + ":" + requestFingerprint));
        }

        @Override
        public boolean scopeTargetOwned(CatalogOwner owner, MaterialScopeType scopeType, String scopeKey) {
            return true;
        }

        @Override
        public MaterialLifecycleResult apply(MaterialLifecycleMutation mutation) {
            this.mutation = mutation;
            this.material = mutation.material();
            applyCount++;
            if (material.lifecycleState() == MaterialLifecycleState.DELETE_PENDING) {
                deletionTaskRequested = true;
            }
            MaterialLifecycleResult result = MaterialLifecycleResult.from(material);
            applied.put(mutation.action().name() + ":" + mutation.requestFingerprint(), result);
            return result;
        }

        @Override
        public boolean originConversationAvailable(CatalogOwner owner, String conversationId) {
            return true;
        }

        @Override
        public MaterialDeletionImpact findDeletionImpact(CatalogOwner owner, String materialId) {
            return new MaterialDeletionImpact(materialId, material.lifecycleGeneration(),
                    List.of("version_1"), List.of(), List.of(), List.of(), List.of());
        }

        @Override
        public List<MaterialExpiryCandidate> findExpiredTemporary(Instant now, int limit) {
            return expiredTemporary;
        }

        @Override
        public List<MaterialExpiryCandidate> findExpiredTrash(Instant now, int limit) {
            return List.of();
        }

        @Override
        public List<MaterialLifecycleCandidate> findOwnerDeletionCandidates(String ownerKey, int limit) {
            return List.of();
        }
    }
}
