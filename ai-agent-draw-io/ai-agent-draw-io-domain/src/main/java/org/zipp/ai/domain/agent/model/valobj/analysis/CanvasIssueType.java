package org.zipp.ai.domain.agent.model.valobj.analysis;

public enum CanvasIssueType {
    INVALID_XML,
    DUP_ID,
    MISSING_GEOMETRY,
    BROKEN_EDGE,
    NODE_OVERLAP,
    EDGE_NODE_CROSSING,
    REMOVABLE_WAYPOINT,
    OPAQUE_TEXT_BACKGROUND,
    /** Label text is estimated to overflow its shape's bounds. */
    TEXT_OVERFLOW,
    /** A region container is much larger than the content it holds. */
    OVERSIZED_REGION,
    /** Too many distinct fill colors for one coherent palette. */
    PALETTE_INCOHERENT,
    /** Nodes in one row/column have visibly irregular gaps. */
    UNEVEN_SPACING,
    /** An edge label likely sits on a node body or on another edge label. */
    EDGE_LABEL_COLLISION,
    /** An edge exits or enters from the side opposite to its visual flow. */
    PORT_DIRECTION_MISMATCH,
    /** Parallel or opposite edges share the same visual track and look stacked. */
    PARALLEL_EDGE_OVERLAP,
    /** Multiple unrelated edges reuse the same port track on one node side. */
    NODE_SIDE_PORT_CROWDING,
    /** A side port sits too close to a rounded node corner for a clean arrow head. */
    PORT_CORNER_PROXIMITY
}
