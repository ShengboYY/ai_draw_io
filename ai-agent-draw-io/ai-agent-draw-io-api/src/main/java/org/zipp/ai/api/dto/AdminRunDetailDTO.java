package org.zipp.ai.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class AdminRunDetailDTO {

    private AdminRunMetadataDTO run;
    private List<AdminRunStepDTO> steps;
    private List<AdminLlmCallDTO> llmCalls;
    private List<AdminToolCallDTO> toolCalls;
    private List<AdminTraceEventDTO> traceEvents;
    private List<AdminRunTimelineEventDTO> timeline;
}
