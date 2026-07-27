package org.zipp.ai.domain.ingestion.model.valobj;

import org.zipp.ai.domain.account.model.valobj.OwnerType;

import java.time.Instant;

/** Persistence snapshot used to rehydrate the aggregate without exposing mutable setters. */
public record UploadSessionSnapshot(String id,
                                    OwnerType ownerType,
                                    String ownerKey,
                                    String idempotencyKey,
                                    String displayName,
                                    String declaredMediaType,
                                    long expectedSize,
                                    String expectedSha256,
                                    UploadTarget target,
                                    String newVersionOfMaterialId,
                                    String quarantineBucket,
                                    String quarantineKey,
                                    Instant policyExpiresAt,
                                    Instant createdAt,
                                    UploadSessionState state,
                                    QuarantineObjectVersion pinnedObject,
                                    long generation,
                                    String materialId,
                                    String versionId,
                                    String errorCode,
                                    boolean securityValidated) {
}
