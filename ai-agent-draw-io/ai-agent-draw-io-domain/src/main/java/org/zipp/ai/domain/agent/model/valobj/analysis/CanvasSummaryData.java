package org.zipp.ai.domain.agent.model.valobj.analysis;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CanvasSummaryData {

    private int nodeCount;

    private int edgeCount;

    private double x;

    private double y;

    private double width;

    private double height;

    private String summary;

}
