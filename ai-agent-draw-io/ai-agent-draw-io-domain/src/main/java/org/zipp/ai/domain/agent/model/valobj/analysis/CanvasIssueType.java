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
    PORT_CORNER_PROXIMITY,
    /** Two rendered edge paths cross away from a shared endpoint. */
    EDGE_EDGE_CROSSING,
    /** Two edge segments occupy the same horizontal or vertical track. */
    EDGE_COLLINEAR_OVERLAP,
    /** A secondary or return edge enters the protected main-flow lane. */
    PROTECTED_LANE_INTRUSION,
    /** A return edge stays inside the content bounds instead of using an outer gutter. */
    RETURN_GUTTER_VIOLATION,
    /** The rendered direction conflicts with the selected diagram layout. */
    EDGE_DIRECTION_MISMATCH,
    /** Multiple geometric conflicts make an edge difficult to trace visually. */
    AMBIGUOUS_EDGE_TRACE,
    /** The route cannot be reconstructed precisely enough for geometric repair. */
    ANALYSIS_LIMITATION
}
