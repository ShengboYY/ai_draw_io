package org.zipp.ai.api.dto;

import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
public class ImportAnonymousWorkspaceResponseDTO {

    private int importedCount;
    private List<DiagramSummaryResponseDTO> diagrams = Collections.emptyList();

}
