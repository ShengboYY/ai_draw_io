package org.zipp.ai.application.turn.agent;

/** One declared ID-scoped XML mutation; the store never accepts an undeclared full-canvas edit. */
public record DraftCellMutation(
        DraftMutationType type,
        String cellId,
        String cellXml
) {

    public DraftCellMutation {
        cellId = cellId == null ? "" : cellId.trim();
        cellXml = cellXml == null ? "" : cellXml.trim();
        if (type == null || cellId.isBlank() || cellId.length() > 255
                || cellId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("draft cell mutation is invalid");
        }
        if (type == DraftMutationType.DELETE && !cellXml.isEmpty()) {
            throw new IllegalArgumentException("delete mutation must not contain cellXml");
        }
        if (type != DraftMutationType.DELETE && cellXml.isEmpty()) {
            throw new IllegalArgumentException("add and replace mutations require cellXml");
        }
    }

    public static DraftCellMutation add(String cellId, String cellXml) {
        return new DraftCellMutation(DraftMutationType.ADD, cellId, cellXml);
    }

    public static DraftCellMutation replace(String cellId, String cellXml) {
        return new DraftCellMutation(DraftMutationType.REPLACE, cellId, cellXml);
    }

    public static DraftCellMutation delete(String cellId) {
        return new DraftCellMutation(DraftMutationType.DELETE, cellId, "");
    }
}
