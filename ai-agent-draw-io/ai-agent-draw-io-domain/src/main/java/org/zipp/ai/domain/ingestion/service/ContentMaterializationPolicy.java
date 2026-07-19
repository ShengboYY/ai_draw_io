package org.zipp.ai.domain.ingestion.service;

import org.zipp.ai.domain.ingestion.model.valobj.OwnedContentBlob;
import org.zipp.ai.domain.ingestion.model.valobj.OwnedMaterialVersion;

public final class ContentMaterializationPolicy {

    public enum Action { REUSE_VERSION, CREATE_VERSION, CREATE_MATERIAL }

    public Decision decide(String explicitTargetMaterialId,
                           OwnedContentBlob existingBlob,
                           OwnedMaterialVersion reusableOwnedVersion,
                           OwnedMaterialVersion versionInExplicitTarget) {
        if (explicitTargetMaterialId == null && reusableOwnedVersion != null) {
            return Decision.reuse(reusableOwnedVersion, existingBlob);
        }
        if (explicitTargetMaterialId != null && versionInExplicitTarget != null) {
            return Decision.reuse(versionInExplicitTarget, existingBlob);
        }
        if (explicitTargetMaterialId != null && versionInExplicitTarget == null) {
            return Decision.createVersion(explicitTargetMaterialId, existingBlob);
        }
        return Decision.createMaterial(existingBlob);
    }

    public record Decision(Action action, String materialId, String versionId, String revisionId,
                           OwnedContentBlob contentBlob) {
        private static Decision reuse(OwnedMaterialVersion version, OwnedContentBlob blob) {
            return new Decision(Action.REUSE_VERSION, version.materialId(), version.versionId(),
                    version.revisionId(), blob);
        }

        private static Decision createVersion(String materialId, OwnedContentBlob blob) {
            return new Decision(Action.CREATE_VERSION, materialId, null, null, blob);
        }

        private static Decision createMaterial(OwnedContentBlob blob) {
            return new Decision(Action.CREATE_MATERIAL, null, null, null, blob);
        }
    }
}
