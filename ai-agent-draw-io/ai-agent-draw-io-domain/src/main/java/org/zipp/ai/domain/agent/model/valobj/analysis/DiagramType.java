package org.zipp.ai.domain.agent.model.valobj.analysis;

import java.util.Locale;

public enum DiagramType {
    FLOWCHART,
    ARCHITECTURE,
    SEQUENCE,
    ER,
    UML,
    STATE,
    USE_CASE,
    CONCEPT,
    ILLUSTRATION,
    GENERIC;

    public static DiagramType from(String value) {
        if (value == null || value.isBlank()) {
            return GENERIC;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "flowchart", "flow_chart" -> FLOWCHART;
            case "architecture", "arch" -> ARCHITECTURE;
            case "sequence", "sequence_diagram" -> SEQUENCE;
            case "er", "erd", "entity_relationship" -> ER;
            case "uml", "uml_class", "class", "class_diagram" -> UML;
            case "state", "state_diagram", "state_machine" -> STATE;
            case "usecase", "use_case", "use_case_diagram" -> USE_CASE;
            case "concept", "concept_map", "mind_map", "mindmap" -> CONCEPT;
            case "illustration", "drawing", "freeform" -> ILLUSTRATION;
            default -> GENERIC;
        };
    }
}
