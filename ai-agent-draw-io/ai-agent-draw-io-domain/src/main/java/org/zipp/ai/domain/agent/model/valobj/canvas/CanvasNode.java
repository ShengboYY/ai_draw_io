package org.zipp.ai.domain.agent.model.valobj.canvas;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CanvasNode {

    private String id;
    private String parentId;
    private String label;
    private String rawLabel;
    private String style;
    private double x;
    private double y;
    private double width;
    private double height;
    private boolean text;
    private boolean container;

    public double centerX() {
        return x + width / 2D;
    }

    public double centerY() {
        return y + height / 2D;
    }

    public double maxX() {
        return x + width;
    }

    public double maxY() {
        return y + height;
    }

}
