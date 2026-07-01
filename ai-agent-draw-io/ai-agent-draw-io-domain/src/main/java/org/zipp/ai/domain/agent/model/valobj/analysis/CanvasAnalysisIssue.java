package org.zipp.ai.domain.agent.model.valobj.analysis;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CanvasAnalysisIssue {

    private CanvasIssueType type;

    private String category;

    private String severity;

    private List<String> targetCellIds;

    private String message;

    private String repairability;

}
