package org.zipp.ai.api.dto;

import java.util.List;
import java.util.Set;

public record MaterialPageSetDTO(String materialId, String versionId, String revisionId,
                                 int revisionNo, String processingStatus, int progress,
                                 Set<Integer> excludedPages, List<MaterialPageDTO> pages) {
}
