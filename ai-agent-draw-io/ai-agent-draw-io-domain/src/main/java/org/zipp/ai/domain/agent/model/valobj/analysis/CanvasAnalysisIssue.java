package org.zipp.ai.domain.agent.model.valobj.analysis;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class CanvasAnalysisIssue {

    private CanvasIssueType type;

    private String category;

    private String severity;

    private List<String> targetCellIds;

    private String message;

    private String repairability;

    /** Deterministic confidence in the geometry used to produce this issue. */
    private Double confidence;

    /** Structured evidence consumed by the typed issue contract and later repair planning. */
    private Map<String, Object> evidence;

}
