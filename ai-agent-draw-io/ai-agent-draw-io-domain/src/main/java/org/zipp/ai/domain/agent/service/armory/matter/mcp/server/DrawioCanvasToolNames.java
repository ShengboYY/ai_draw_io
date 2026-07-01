package org.zipp.ai.domain.agent.service.armory.matter.mcp.server;

import java.util.List;
import java.util.Set;

public final class DrawioCanvasToolNames {

    public static final String CREATE_DIAGRAM = "create_diagram";
    public static final String MODIFY_DIAGRAM = "modify_diagram";
    public static final String OPTIMIZE_DIAGRAM = "optimize_diagram";
    public static final String PATCH_CELLS = "patch_cells";

    public static final String DISPLAY_DIAGRAM = "display_diagram";
    public static final String APPEND_DIAGRAM = "append_diagram";
    public static final String EDIT_DIAGRAM = "edit_diagram";
    public static final String UPDATE_CELLS = "update_cells";
    public static final String ROUTE_EDGES = "route_edges";
    public static final String CONTINUE_DIAGRAM = "continue_diagram";

    public static final List<String> CONSOLIDATED_TOOL_NAMES = List.of(
            CREATE_DIAGRAM,
            MODIFY_DIAGRAM,
            OPTIMIZE_DIAGRAM
    );

    public static final Set<String> DRAWING_RESULT_TOOL_NAMES = Set.of(
            CREATE_DIAGRAM,
            MODIFY_DIAGRAM,
            DISPLAY_DIAGRAM,
            APPEND_DIAGRAM,
            EDIT_DIAGRAM,
            OPTIMIZE_DIAGRAM,
            UPDATE_CELLS,
            ROUTE_EDGES,
            CONTINUE_DIAGRAM
    );

    public static final Set<String> LOCAL_EDIT_TOOL_NAMES = Set.of(
            MODIFY_DIAGRAM,
            EDIT_DIAGRAM,
            UPDATE_CELLS,
            ROUTE_EDGES
    );

    private DrawioCanvasToolNames() {
    }
}
