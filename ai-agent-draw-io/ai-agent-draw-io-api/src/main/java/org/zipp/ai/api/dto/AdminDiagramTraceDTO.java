package org.zipp.ai.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class AdminDiagramTraceDTO {

    private AdminRunMetadataDTO run;
    private AdminDiagramTraceSummaryDTO summary;
    private List<AdminDiagramTraceSpanDTO> spans;
    private List<AdminDiagramSnapshotDTO> snapshots;
    private List<AdminDiagramFindingDTO> findings;
    private AdminPayloadAvailabilityDTO payloadAvailability;
}
