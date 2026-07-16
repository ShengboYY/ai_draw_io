package org.zipp.ai.domain.agent.service.analysis;

import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramQualityProfile;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;
import org.zipp.ai.domain.agent.model.valobj.analysis.LayoutFamily;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DiagramQualityProfileCatalog {

    public static final String CURRENT_VERSION = "quality-profile-v1";

    private static final Set<CanvasIssueType> STRUCTURAL_ISSUES = EnumSet.of(
            CanvasIssueType.INVALID_XML,
            CanvasIssueType.DUP_ID,
            CanvasIssueType.MISSING_GEOMETRY,
            CanvasIssueType.BROKEN_EDGE,
            CanvasIssueType.ANALYSIS_LIMITATION);
    private static final Set<CanvasIssueType> ROUTE_REPAIR_ISSUES = EnumSet.of(
            CanvasIssueType.EDGE_NODE_CROSSING,
            CanvasIssueType.EDGE_LABEL_COLLISION,
            CanvasIssueType.PORT_DIRECTION_MISMATCH,
            CanvasIssueType.PARALLEL_EDGE_OVERLAP,
            CanvasIssueType.NODE_SIDE_PORT_CROWDING,
            CanvasIssueType.PORT_CORNER_PROXIMITY);

    private final Map<DiagramType, DiagramQualityProfile> profiles;

    public DiagramQualityProfileCatalog() {
        this.profiles = buildProfiles();
    }

    public DiagramQualityProfile resolve(DiagramType diagramType) {
        return profiles.getOrDefault(diagramType == null ? DiagramType.GENERIC : diagramType,
                profiles.get(DiagramType.GENERIC));
    }

    public DiagramQualityProfile resolve(DiagramType diagramType, String requestedVersion) {
        // Only v1 exists today. An unknown version falls back to the conservative generic contract
        // instead of silently applying a different diagram-specific repair policy.
        if (requestedVersion != null && !requestedVersion.isBlank() && !CURRENT_VERSION.equals(requestedVersion)) {
            return profiles.get(DiagramType.GENERIC);
        }
        return resolve(diagramType);
    }

    private Map<DiagramType, DiagramQualityProfile> buildProfiles() {
        Map<DiagramType, DiagramQualityProfile> result = new EnumMap<>(DiagramType.class);
        Set<CanvasIssueType> allIssues = EnumSet.allOf(CanvasIssueType.class);
        Set<CanvasIssueType> conceptIssues = withStructural(
                CanvasIssueType.NODE_OVERLAP,
                CanvasIssueType.EDGE_NODE_CROSSING,
                CanvasIssueType.OPAQUE_TEXT_BACKGROUND,
                CanvasIssueType.TEXT_OVERFLOW,
                CanvasIssueType.OVERSIZED_REGION,
                CanvasIssueType.PALETTE_INCOHERENT);
        Set<CanvasIssueType> sequenceIssues = withStructural(
                CanvasIssueType.NODE_OVERLAP,
                CanvasIssueType.OPAQUE_TEXT_BACKGROUND,
                CanvasIssueType.TEXT_OVERFLOW,
                CanvasIssueType.OVERSIZED_REGION,
                CanvasIssueType.PALETTE_INCOHERENT,
                CanvasIssueType.EDGE_LABEL_COLLISION);

        result.put(DiagramType.FLOWCHART, profile(DiagramType.FLOWCHART, LayoutFamily.TOP_DOWN,
                Set.of(LayoutFamily.TOP_DOWN, LayoutFamily.LEFT_RIGHT), allIssues, ROUTE_REPAIR_ISSUES,
                "Keep the main decision flow readable and route return branches through clear outer gutters."));
        result.put(DiagramType.ARCHITECTURE, profile(DiagramType.ARCHITECTURE, LayoutFamily.LAYERED,
                Set.of(LayoutFamily.LAYERED, LayoutFamily.TOP_DOWN, LayoutFamily.LEFT_RIGHT), allIssues,
                ROUTE_REPAIR_ISSUES, "Preserve system boundaries, hierarchy, and unambiguous dependency paths."));
        result.put(DiagramType.SEQUENCE, profile(DiagramType.SEQUENCE, LayoutFamily.TIMELINE,
                Set.of(LayoutFamily.TIMELINE), sequenceIssues, Set.of(),
                "Keep lifelines ordered and messages readable in chronological order."));
        result.put(DiagramType.ER, profile(DiagramType.ER, LayoutFamily.TABLE_RELATIONSHIP,
                Set.of(LayoutFamily.TABLE_RELATIONSHIP), allIssues, ROUTE_REPAIR_ISSUES,
                "Keep entity fields readable and relationship cardinality traceable."));
        result.put(DiagramType.UML, profile(DiagramType.UML, LayoutFamily.LAYERED,
                Set.of(LayoutFamily.LAYERED, LayoutFamily.TOP_DOWN, LayoutFamily.LEFT_RIGHT), allIssues,
                ROUTE_REPAIR_ISSUES, "Keep type hierarchy and relationship direction visually explicit."));
        result.put(DiagramType.STATE, profile(DiagramType.STATE, LayoutFamily.TOP_DOWN,
                Set.of(LayoutFamily.TOP_DOWN, LayoutFamily.LEFT_RIGHT), allIssues, ROUTE_REPAIR_ISSUES,
                "Keep transitions traceable and distinguish terminal states from ordinary states."));
        result.put(DiagramType.USE_CASE, profile(DiagramType.USE_CASE, LayoutFamily.LAYERED,
                Set.of(LayoutFamily.LAYERED, LayoutFamily.LEFT_RIGHT), allIssues, ROUTE_REPAIR_ISSUES,
                "Keep actors outside the system boundary and associations easy to trace."));
        result.put(DiagramType.CONCEPT, profile(DiagramType.CONCEPT, LayoutFamily.RADIAL,
                Set.of(LayoutFamily.RADIAL, LayoutFamily.LAYERED), conceptIssues, Set.of(),
                "Preserve semantic grouping and radial hierarchy; do not force orthogonal flowchart routing."));
        result.put(DiagramType.ILLUSTRATION, profile(DiagramType.ILLUSTRATION, LayoutFamily.FREEFORM,
                Set.of(LayoutFamily.FREEFORM), STRUCTURAL_ISSUES, Set.of(),
                "Review composition visually while limiting deterministic checks to structural integrity."));
        result.put(DiagramType.GENERIC, profile(DiagramType.GENERIC, LayoutFamily.FREEFORM,
                EnumSet.allOf(LayoutFamily.class), withStructural(CanvasIssueType.NODE_OVERLAP), Set.of(),
                "Use conservative high-confidence checks until the diagram type is known."));
        return Map.copyOf(result);
    }

    private DiagramQualityProfile profile(DiagramType type,
                                          LayoutFamily defaultLayout,
                                          Set<LayoutFamily> allowedLayouts,
                                          Set<CanvasIssueType> enabledIssues,
                                          Set<CanvasIssueType> automaticIssues,
                                          String rubric) {
        return new DiagramQualityProfile(CURRENT_VERSION, type, defaultLayout, allowedLayouts,
                enabledIssues, automaticIssues, List.of(rubric));
    }

    private Set<CanvasIssueType> withStructural(CanvasIssueType... issueTypes) {
        EnumSet<CanvasIssueType> result = EnumSet.copyOf(STRUCTURAL_ISSUES);
        result.addAll(List.of(issueTypes));
        return result;
    }
}
