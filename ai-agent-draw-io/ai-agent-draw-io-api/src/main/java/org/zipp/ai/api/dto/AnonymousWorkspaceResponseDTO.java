package org.zipp.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Public response intentionally contains only the non-secret owner identifier. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnonymousWorkspaceResponseDTO {
    private String ownerId;
}
