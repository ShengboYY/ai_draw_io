package org.zipp.ai.domain.material.service;

import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialLifecyclePort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** User-facing impact/confirmation boundary for irreversible deletion. */
public final class MaterialDeletionService {
    private final MaterialLifecyclePort lifecycle;
    private final MaterialLifecycleService commands;
    private final MaterialDeletionConfirmationService confirmations;

    public MaterialDeletionService(MaterialLifecyclePort lifecycle, MaterialLifecycleService commands,
                                   MaterialDeletionConfirmationService confirmations) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
    }

    public MaterialDeletionPreview preview(CatalogOwner owner, String materialId) {
        owner.requireRegisteredUser();
        MaterialDeletionImpact impact = lifecycle.findDeletionImpact(owner, required(materialId));
        var confirmation = confirmations.issue(impact);
        return new MaterialDeletionPreview(impact, confirmation.token(), confirmation.expiresAt());
    }

    public MaterialLifecycleResult confirm(CatalogOwner owner, String materialId, long expectedGeneration,
                                           String confirmationToken, String idempotencyKey) {
        owner.requireRegisteredUser();
        String id = required(materialId);
        String key = requiredKey(idempotencyKey);
        String fingerprint = sha256(MaterialLifecycleAction.PERMANENT_DELETE.name() + ":" + key);
        MaterialLifecycleResult previous = lifecycle.findApplied(owner, id,
                MaterialLifecycleAction.PERMANENT_DELETE, fingerprint).orElse(null);
        if (previous != null) return previous;
        MaterialDeletionImpact impact = lifecycle.findDeletionImpact(owner, id);
        if (impact.lifecycleGeneration() != expectedGeneration
                || !confirmations.verifies(confirmationToken, impact)) {
            throw new CatalogOperationException(CatalogErrorCode.DELETION_CONFIRMATION_INVALID);
        }
        return commands.requestPermanentDeletion(owner, materialId, idempotencyKey,
                impact.fingerprint());
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("materialId is required");
        return value.trim();
    }

    private String requiredKey(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key is invalid");
        }
        return value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
