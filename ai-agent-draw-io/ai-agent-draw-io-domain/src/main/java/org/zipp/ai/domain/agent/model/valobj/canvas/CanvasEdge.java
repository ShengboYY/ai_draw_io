package org.zipp.ai.domain.agent.model.valobj.canvas;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CanvasEdge {

    private String id;
    private String parentId;
    private String label;
    private String rawLabel;
    private String style;
    private String source;
    private String target;
    private List<CanvasPoint> points;

}
