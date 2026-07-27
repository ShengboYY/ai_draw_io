package org.zipp.ai.api.dto;

public record InitiateMaterialUploadRequestDTO(String displayName,
                                               String mediaType,
                                               long byteSize,
                                               String sha256,
                                               UploadTargetDTO target,
                                               String newVersionOfMaterialId,
                                               Integer batchFileCount) {
    public record UploadTargetDTO(String scopeType, String scopeId, String retentionClass, String diagramId) {
    }
}
