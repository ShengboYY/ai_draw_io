package org.zipp.ai.application.turn.agent;

/** Bounded cell observation; raw XML is present only for targeted inspections. */
public record InspectedDiagramCell(
        String id,
        String label,
        String kind,
        String parentId,
        String source,
        String target,
        double x,
        double y,
        double width,
        double height,
        String rawXml
) {

    public InspectedDiagramCell {
        id = text(id);
        label = text(label);
        kind = text(kind);
        parentId = text(parentId);
        source = text(source);
        target = text(target);
        rawXml = text(rawXml);
        if (id.isBlank()) {
            throw new IllegalArgumentException("inspected cell id is required");
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
