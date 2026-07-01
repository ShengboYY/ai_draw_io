package org.zipp.ai.domain.agent.model.valobj.analysis;

import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;

@Data
@Builder
public class CanvasCellData {

    private String id;

    private String label;

    private String kind;

    private String style;

    private String parentId;

    private String source;

    private String target;

    private CanvasPointData sourcePoint;

    private CanvasPointData targetPoint;

    private List<CanvasPointData> points;

    private double x;

    private double y;

    private double width;

    private double height;

    private String rawXml;

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

    public boolean matches(String query) {
        return contains(id, query)
                || contains(label, query)
                || contains(style, query)
                || contains(source, query)
                || contains(target, query);
    }

    private boolean contains(String value, String query) {
        return StringUtils.defaultString(value).toLowerCase(Locale.ROOT).contains(query);
    }

}
