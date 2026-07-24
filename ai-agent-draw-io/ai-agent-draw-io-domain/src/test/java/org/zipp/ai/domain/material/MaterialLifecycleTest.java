package org.zipp.ai.domain.material;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.aggregate.Material;
import org.zipp.ai.domain.material.model.valobj.MaterialKind;
import org.zipp.ai.domain.material.model.valobj.MaterialLifecycleState;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeLink;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaterialLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void meaningfulActivityExtendsOnlyAnActiveTemporaryMaterial() {
        Material material = Material.createTemporary(
                "mat_1", OwnerType.USER, "usr_1", MaterialKind.PDF,
                "Agile Guide", "conv_1", NOW);

        material.recordMeaningfulActivity(NOW.plus(Duration.ofHours(20)));

        assertEquals(NOW.plus(Duration.ofHours(44)), material.expiresAt());
        assertEquals(0L, material.lifecycleGeneration());
    }

    @Test
    void retainedUploadStartsInItsDurableScopeWithoutATemporaryExpiry() {
        Material material = Material.createRetained(
                "mat_library", OwnerType.USER, "usr_1", MaterialKind.PDF, "Agile Guide",
                MaterialScopeLink.of(MaterialScopeType.LIBRARY, "personal", "usr_1"), NOW);

        assertEquals(RetentionClass.RETAINED, material.retentionClass());
        assertEquals(null, material.expiresAt());
        assertEquals(null, material.originConversationId());
        assertEquals(1, material.scopeLinks().size());
    }

    @Test
    void retainingMaterialAddsScopeAndInvalidatesLateTtlActivity() {
        Material material = Material.createTemporary(
                "mat_1", OwnerType.USER, "usr_1", MaterialKind.PDF,
                "Agile Guide", "conv_1", NOW);
        long expectedGeneration = material.lifecycleGeneration();

        material.retain(MaterialScopeLink.of(MaterialScopeType.LIBRARY, "library", "usr_1"), NOW);

        assertEquals(RetentionClass.RETAINED, material.retentionClass());
        assertEquals(null, material.expiresAt());
        assertEquals(expectedGeneration + 1, material.lifecycleGeneration());
        assertThrows(IllegalStateException.class,
                () -> material.recordMeaningfulActivity(NOW.plusSeconds(1)));
    }

    @Test
    void rehydratedTemporaryMaterialRetainsThroughTheSameAggregateInvariant() {
        Material material = Material.rehydrateTemporaryActive(
                "mat_1", OwnerType.USER, "usr_1", MaterialKind.PDF, "Agile Guide", "conv_1",
                4, NOW.minus(Duration.ofHours(2)), NOW.plus(Duration.ofHours(22)));

        material.retain(MaterialScopeLink.of(MaterialScopeType.LIBRARY, "personal", "usr_1"), NOW);

        assertEquals(RetentionClass.RETAINED, material.retentionClass());
        assertEquals(5, material.lifecycleGeneration());
        assertEquals(null, material.expiresAt());
    }

    @Test
    void conversationScopeCannotConvertTemporaryMaterialToRetained() {
        Material material = Material.createTemporary(
                "mat_1", OwnerType.USER, "usr_1", MaterialKind.PDF,
                "Agile Guide", "conv_1", NOW);

        assertThrows(IllegalArgumentException.class,
                () -> material.retain(
                        MaterialScopeLink.of(MaterialScopeType.CONVERSATION, "conv_1", "usr_1"), NOW));
        assertEquals(RetentionClass.TEMPORARY, material.retentionClass());
    }

    @Test
    void expiredTemporaryMaterialCannotBePromotedAfterItsDeadline() {
        Material material = Material.rehydrateTemporaryActive(
                "mat_1", OwnerType.USER, "usr_1", MaterialKind.PDF, "Agile Guide", "conv_1",
                1, NOW.minus(Duration.ofDays(2)), NOW.minusSeconds(1));

        assertThrows(IllegalStateException.class, () -> material.retain(
                MaterialScopeLink.of(MaterialScopeType.LIBRARY, "personal", "usr_1"), NOW));
    }

    @Test
    void promotedMaterialCanRetainItsConversationLinkAlongsideADurableScope() {
        Material material = Material.rehydrateRetainedActive(
                "mat_1", OwnerType.USER, "usr_1", MaterialKind.PDF, "Agile Guide", 2, NOW,
                Set.of(MaterialScopeLink.of(MaterialScopeType.CONVERSATION, "conv_1", "usr_1"),
                        MaterialScopeLink.of(MaterialScopeType.LIBRARY, "personal", "usr_1")));

        assertEquals(RetentionClass.RETAINED, material.retentionClass());
        assertEquals(2, material.scopeLinks().size());
    }

    @Test
    void anonymousMaterialSkipsTrashWhileRegisteredMaterialCanRestore() {
        Material anonymous = Material.createTemporary(
                "mat_anon", OwnerType.ANONYMOUS, "anon_1", MaterialKind.IMAGE,
                "workflow", "conv_anon", NOW);
        anonymous.remove(NOW);
        assertEquals(MaterialLifecycleState.DELETE_PENDING, anonymous.lifecycleState());

        Material registered = Material.createTemporary(
                "mat_user", OwnerType.USER, "usr_1", MaterialKind.IMAGE,
                "workflow", "conv_user", NOW);
        registered.remove(NOW);
        assertEquals(MaterialLifecycleState.TRASHED, registered.lifecycleState());
        registered.restore(NOW.plus(Duration.ofDays(2)));
        assertEquals(MaterialLifecycleState.ACTIVE, registered.lifecycleState());
        assertEquals(NOW.plus(Duration.ofDays(3)), registered.expiresAt());
    }

    @Test
    void retainedTrashWithoutScopesCanBeRehydratedButNeedsAnExplicitRestoreTarget() {
        Material material = Material.rehydrateTrashed(
                "mat_orphaned", OwnerType.USER, "usr_1", MaterialKind.PDF, "Guide",
                RetentionClass.RETAINED, null, 3, NOW, null,
                NOW.plus(Duration.ofDays(30)), Set.of());

        assertEquals(MaterialLifecycleState.TRASHED, material.lifecycleState());
        assertEquals(0, material.scopeLinks().size());
        assertThrows(IllegalStateException.class, () -> material.restore(NOW.plusSeconds(1)));
    }
}
