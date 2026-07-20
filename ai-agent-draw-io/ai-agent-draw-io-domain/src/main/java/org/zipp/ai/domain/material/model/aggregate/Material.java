package org.zipp.ai.domain.material.model.aggregate;

import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.valobj.MaterialKind;
import org.zipp.ai.domain.material.model.valobj.MaterialLifecycleState;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeLink;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class Material {

    private static final Duration TEMPORARY_TTL = Duration.ofHours(24);
    private static final Duration TRASH_TTL = Duration.ofDays(30);

    private final String id;
    private final OwnerType ownerType;
    private final String ownerKey;
    private final MaterialKind kind;
    private final String displayName;
    private final String originConversationId;
    private final Set<MaterialScopeLink> scopeLinks = new LinkedHashSet<>();
    private RetentionClass retentionClass;
    private MaterialLifecycleState lifecycleState;
    private long lifecycleGeneration;
    private Instant lastMeaningfulActivityAt;
    private Instant expiresAt;
    private Instant trashExpiresAt;
    private Instant deletedAt;

    private Material(String id, OwnerType ownerType, String ownerKey, MaterialKind kind,
                     String displayName, String originConversationId, MaterialScopeLink initialScope,
                     RetentionClass initialRetention, Instant createdAt) {
        this.id = requireText(id, "id");
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        this.ownerKey = requireText(ownerKey, "ownerKey");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.displayName = requireText(displayName, "displayName");
        this.retentionClass = Objects.requireNonNull(initialRetention, "initialRetention");
        this.originConversationId = initialRetention == RetentionClass.TEMPORARY
                ? requireText(originConversationId, "originConversationId") : null;
        Instant now = Objects.requireNonNull(createdAt, "createdAt");
        this.lifecycleState = MaterialLifecycleState.ACTIVE;
        this.lastMeaningfulActivityAt = now;
        if (initialRetention == RetentionClass.TEMPORARY) {
            this.expiresAt = now.plus(TEMPORARY_TTL);
        } else {
            if (ownerType == OwnerType.ANONYMOUS) {
                throw new IllegalArgumentException("anonymous materials cannot start retained");
            }
            MaterialScopeLink retainedScope = Objects.requireNonNull(initialScope, "initialScope");
            if (retainedScope.scopeType() == MaterialScopeType.CONVERSATION) {
                throw new IllegalArgumentException("retained material requires a durable scope");
            }
            scopeLinks.add(retainedScope);
        }
    }

    public static Material createTemporary(String id, OwnerType ownerType, String ownerKey, MaterialKind kind,
                                           String displayName, String originConversationId, Instant createdAt) {
        return new Material(id, ownerType, ownerKey, kind, displayName, originConversationId, null,
                RetentionClass.TEMPORARY, createdAt);
    }

    public static Material createRetained(String id, OwnerType ownerType, String ownerKey, MaterialKind kind,
                                          String displayName, MaterialScopeLink initialScope, Instant createdAt) {
        return new Material(id, ownerType, ownerKey, kind, displayName, null, initialScope,
                RetentionClass.RETAINED, createdAt);
    }

    public static Material rehydrateTemporaryActive(String id, OwnerType ownerType, String ownerKey,
                                                     MaterialKind kind, String displayName,
                                                     String originConversationId, long lifecycleGeneration,
                                                     Instant lastMeaningfulActivityAt, Instant expiresAt) {
        if (lifecycleGeneration < 0) {
            throw new IllegalArgumentException("lifecycleGeneration cannot be negative");
        }
        Instant activity = Objects.requireNonNull(lastMeaningfulActivityAt, "lastMeaningfulActivityAt");
        Instant expiry = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiry.isAfter(activity)) {
            throw new IllegalArgumentException("temporary material expiry must follow meaningful activity");
        }
        Material material = createTemporary(id, ownerType, ownerKey, kind, displayName,
                originConversationId, activity);
        // Rehydration restores persisted concurrency facts before applying a new domain transition.
        material.lifecycleGeneration = lifecycleGeneration;
        material.lastMeaningfulActivityAt = activity;
        material.expiresAt = expiry;
        return material;
    }

    /** Restores an active retained material with its durable discovery scopes. */
    public static Material rehydrateRetainedActive(String id, OwnerType ownerType, String ownerKey,
                                                   MaterialKind kind, String displayName,
                                                   long lifecycleGeneration,
                                                   Instant lastMeaningfulActivityAt,
                                                   Set<MaterialScopeLink> scopes) {
        Set<MaterialScopeLink> restoredScopes = requireDurableScopes(scopes);
        MaterialScopeLink first = firstDurableScope(restoredScopes);
        Material material = createRetained(id, ownerType, ownerKey, kind, displayName,
                first, Objects.requireNonNull(lastMeaningfulActivityAt, "lastMeaningfulActivityAt"));
        material.scopeLinks.addAll(restoredScopes);
        material.lifecycleGeneration = nonNegative(lifecycleGeneration);
        return material;
    }

    /** Restores only the recoverable trash state; deleted states use the deletion task aggregate. */
    public static Material rehydrateTrashed(String id, OwnerType ownerType, String ownerKey,
                                            MaterialKind kind, String displayName,
                                            RetentionClass retentionClass, String originConversationId,
                                            long lifecycleGeneration, Instant lastMeaningfulActivityAt,
                                            Instant expiresAt, Instant trashExpiresAt,
                                            Set<MaterialScopeLink> scopes) {
        if (ownerType != OwnerType.USER) {
            throw new IllegalArgumentException("anonymous material cannot be restored from trash");
        }
        Instant activity = Objects.requireNonNull(lastMeaningfulActivityAt, "lastMeaningfulActivityAt");
        Material material;
        if (retentionClass == RetentionClass.TEMPORARY) {
            material = createTemporary(id, ownerType, ownerKey, kind, displayName,
                    originConversationId, activity);
            material.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        } else {
            Set<MaterialScopeLink> restoredScopes = requireDurableScopes(scopes);
            material = createRetained(id, ownerType, ownerKey, kind, displayName,
                    firstDurableScope(restoredScopes), activity);
            material.scopeLinks.addAll(restoredScopes);
        }
        material.lifecycleState = MaterialLifecycleState.TRASHED;
        material.lifecycleGeneration = nonNegative(lifecycleGeneration);
        material.trashExpiresAt = Objects.requireNonNull(trashExpiresAt, "trashExpiresAt");
        return material;
    }

    public void recordMeaningfulActivity(Instant activityAt) {
        requireActive();
        if (retentionClass != RetentionClass.TEMPORARY) {
            throw new IllegalStateException("only temporary materials have a sliding expiry");
        }
        Instant now = Objects.requireNonNull(activityAt, "activityAt");
        if (!now.isBefore(expiresAt)) {
            throw new IllegalStateException("expired material cannot be renewed");
        }
        Instant extended = now.plus(TEMPORARY_TTL);
        if (extended.isAfter(expiresAt)) {
            expiresAt = extended;
        }
        lastMeaningfulActivityAt = now;
    }

    public void retain(MaterialScopeLink scopeLink, Instant retainedAt) {
        requireActive();
        if (ownerType == OwnerType.ANONYMOUS) {
            throw new IllegalStateException("anonymous materials cannot enter a retained scope");
        }
        Instant now = Objects.requireNonNull(retainedAt, "retainedAt");
        if (retentionClass == RetentionClass.TEMPORARY && !now.isBefore(expiresAt)) {
            throw new IllegalStateException("expired material cannot be retained");
        }
        MaterialScopeLink retainedScope = Objects.requireNonNull(scopeLink, "scopeLink");
        // A conversation is the temporary origin scope, not a durable retention decision.
        if (retainedScope.scopeType() == MaterialScopeType.CONVERSATION) {
            throw new IllegalArgumentException("conversation scope cannot retain material");
        }
        scopeLinks.add(retainedScope);
        retentionClass = RetentionClass.RETAINED;
        expiresAt = null;
        lifecycleGeneration++;
    }

    public void remove(Instant removedAt) {
        requireActive();
        Instant now = Objects.requireNonNull(removedAt, "removedAt");
        lifecycleGeneration++;
        // Anonymous content is intentionally unrecoverable once removed or expired.
        if (ownerType == OwnerType.ANONYMOUS) {
            lifecycleState = MaterialLifecycleState.DELETE_PENDING;
            expiresAt = null;
            return;
        }
        lifecycleState = MaterialLifecycleState.TRASHED;
        trashExpiresAt = now.plus(TRASH_TTL);
    }

    public void restore(Instant restoredAt) {
        if (lifecycleState != MaterialLifecycleState.TRASHED || ownerType != OwnerType.USER) {
            throw new IllegalStateException("only a registered owner's trashed material can be restored");
        }
        Instant now = Objects.requireNonNull(restoredAt, "restoredAt");
        lifecycleState = MaterialLifecycleState.ACTIVE;
        trashExpiresAt = null;
        lifecycleGeneration++;
        if (retentionClass == RetentionClass.TEMPORARY) {
            expiresAt = now.plus(TEMPORARY_TTL);
            lastMeaningfulActivityAt = now;
        }
    }

    public void requestPermanentDeletion() {
        if (lifecycleState != MaterialLifecycleState.ACTIVE
                && lifecycleState != MaterialLifecycleState.TRASHED) {
            throw new IllegalStateException("material is not eligible for deletion");
        }
        lifecycleState = MaterialLifecycleState.DELETE_PENDING;
        expiresAt = null;
        trashExpiresAt = null;
        lifecycleGeneration++;
    }

    public void beginDeletion() {
        if (lifecycleState != MaterialLifecycleState.DELETE_PENDING) {
            throw new IllegalStateException("material is not pending deletion");
        }
        lifecycleState = MaterialLifecycleState.DELETING;
    }

    public void markDeleted(Instant deletionTime) {
        if (lifecycleState != MaterialLifecycleState.DELETING) {
            throw new IllegalStateException("material is not being deleted");
        }
        lifecycleState = MaterialLifecycleState.DELETED;
        deletedAt = Objects.requireNonNull(deletionTime, "deletionTime");
        scopeLinks.clear();
    }

    private void requireActive() {
        if (lifecycleState != MaterialLifecycleState.ACTIVE) {
            throw new IllegalStateException("material is not active");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static long nonNegative(long value) {
        if (value < 0) throw new IllegalArgumentException("lifecycleGeneration cannot be negative");
        return value;
    }

    private static Set<MaterialScopeLink> requireDurableScopes(Set<MaterialScopeLink> scopes) {
        LinkedHashSet<MaterialScopeLink> values = new LinkedHashSet<>(
                Objects.requireNonNull(scopes, "scopes"));
        if (values.isEmpty() || values.stream().noneMatch(link ->
                link.scopeType() != MaterialScopeType.CONVERSATION)) {
            throw new IllegalArgumentException("retained material requires a durable scope");
        }
        return Set.copyOf(values);
    }

    private static MaterialScopeLink firstDurableScope(Set<MaterialScopeLink> scopes) {
        return scopes.stream().filter(link -> link.scopeType() != MaterialScopeType.CONVERSATION)
                .findFirst().orElseThrow();
    }

    public String id() { return id; }
    public OwnerType ownerType() { return ownerType; }
    public String ownerKey() { return ownerKey; }
    public MaterialKind kind() { return kind; }
    public String displayName() { return displayName; }
    public String originConversationId() { return originConversationId; }
    public RetentionClass retentionClass() { return retentionClass; }
    public MaterialLifecycleState lifecycleState() { return lifecycleState; }
    public long lifecycleGeneration() { return lifecycleGeneration; }
    public Instant lastMeaningfulActivityAt() { return lastMeaningfulActivityAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant trashExpiresAt() { return trashExpiresAt; }
    public Instant deletedAt() { return deletedAt; }
    public Set<MaterialScopeLink> scopeLinks() { return Collections.unmodifiableSet(scopeLinks); }
}
