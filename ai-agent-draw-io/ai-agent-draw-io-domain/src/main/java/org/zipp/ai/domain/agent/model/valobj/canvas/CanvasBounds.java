package org.zipp.ai.domain.agent.model.valobj.canvas;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CanvasBounds {

    private double x;
    private double y;
    private double width;
    private double height;

    public double maxX() {
        return x + width;
    }

    public double maxY() {
        return y + height;
    }

    public boolean isEmpty() {
        return width <= 0D || height <= 0D;
    }

}
