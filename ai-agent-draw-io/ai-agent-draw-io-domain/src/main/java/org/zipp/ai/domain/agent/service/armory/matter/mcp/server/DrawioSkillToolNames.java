package org.zipp.ai.domain.agent.service.armory.matter.mcp.server;

import java.util.List;

public final class DrawioSkillToolNames {

    public static final String LIST_DRAWIO_SKILLS = "list_drawio_skills";
    public static final String GET_DRAWIO_SKILL = "get_drawio_skill";

    public static final List<String> SKILL_LOOKUP_TOOL_NAMES = List.of(
            LIST_DRAWIO_SKILLS,
            GET_DRAWIO_SKILL
    );

    private DrawioSkillToolNames() {
    }
}
