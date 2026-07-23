package org.zipp.ai.api.dto;

import java.util.List;

/** Public, content-free material capability contract for product clients. */
public record MaterialCapabilitiesDTO(String upload, String catalog, String preview,
                                      String retrieval, String denseRetrieval,
                                      String visualObservation, String directImageConversion,
                                      String anonymousUpload, List<String> acceptedMimeTypes,
                                      int maxBatchFiles) {
    public MaterialCapabilitiesDTO {
        acceptedMimeTypes = List.copyOf(acceptedMimeTypes == null ? List.of() : acceptedMimeTypes);
    }
}
