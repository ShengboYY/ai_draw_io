package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisRequest;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasEvidenceSource;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasRepairability;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramQualityProfile;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;
import org.zipp.ai.domain.agent.model.valobj.analysis.LayoutFamily;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.analysis.DiagramQualityProfileCatalog;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DiagramQualityProfileContractTest {

    private static final String CROSSING_FLOW = """
            <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
            <mxCell id='source' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='120' width='80' height='60' as='geometry'/></mxCell>
            <mxCell id='target' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='120' width='80' height='60' as='geometry'/></mxCell>
            <mxCell id='blocker' value='Blocker' vertex='1' parent='1'><mxGeometry x='210' y='110' width='80' height='80' as='geometry'/></mxCell>
            <mxCell id='edge' edge='1' parent='1' source='source' target='target'><mxGeometry relative='1' as='geometry'/></mxCell>
            </root></mxGraphModel>
            """;
    private static final String BROKEN_EDGE = """
            <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
            <mxCell id='edge' edge='1' parent='1' source='missing' target='also-missing'><mxGeometry relative='1' as='geometry'/></mxCell>
            </root></mxGraphModel>
            """;
    private static final String OVERLAPPING_NODES = """
            <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
            <mxCell id='left' value='Left' vertex='1' parent='1'><mxGeometry x='40' y='40' width='120' height='60' as='geometry'/></mxCell>
            <mxCell id='right' value='Right' vertex='1' parent='1'><mxGeometry x='80' y='60' width='120' height='60' as='geometry'/></mxCell>
            </root></mxGraphModel>
            """;
    private static final String OVERFLOWING_LABEL = """
            <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
            <mxCell id='node' value='This label is intentionally far too long for the small node' vertex='1' parent='1'><mxGeometry x='40' y='40' width='80' height='24' as='geometry'/></mxCell>
            </root></mxGraphModel>
            """;
    private static final String MISSING_GEOMETRY = """
            <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
            <mxCell id='node' value='Missing geometry' vertex='1' parent='1'><mxGeometry x='40' y='40' width='0' height='0' as='geometry'/></mxCell>
            </root></mxGraphModel>
            """;

    @Test
    public void catalogDefinesEveryDiagramTypeAndKeepsGenericRepairConservative() {
        DiagramQualityProfileCatalog catalog = new DiagramQualityProfileCatalog();

        Arrays.stream(DiagramType.values()).forEach(type -> assertNotNull(type.name(), catalog.resolve(type)));
        DiagramQualityProfile generic = catalog.resolve(DiagramType.GENERIC);

        assertEquals(LayoutFamily.FREEFORM, generic.defaultLayoutFamily());
        assertFalse(generic.allowsAutomaticRepair(CanvasIssueType.EDGE_NODE_CROSSING));
    }

    @Test
    public void flowchartAndConceptProfilesUseDifferentRepairPolicies() {
        DiagramQualityProfileCatalog catalog = new DiagramQualityProfileCatalog();

        assertTrue(catalog.resolve(DiagramType.FLOWCHART).enables(CanvasIssueType.EDGE_NODE_CROSSING));
        assertTrue(catalog.resolve(DiagramType.CONCEPT).enables(CanvasIssueType.EDGE_NODE_CROSSING));
        assertTrue(catalog.resolve(DiagramType.FLOWCHART)
                .allowsAutomaticRepair(CanvasIssueType.EDGE_NODE_CROSSING));
        assertFalse(catalog.resolve(DiagramType.CONCEPT)
                .allowsAutomaticRepair(CanvasIssueType.EDGE_NODE_CROSSING));
    }

    @Test
    public void everyProfileHasAMinimalValidAndInvalidContractFixture() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        for (DiagramType type : DiagramType.values()) {
            ProfileContractFixture fixture = fixture(type);
            CanvasAnalysis valid = analyzer.analyze(request(fixture.validXml(), type));
            CanvasAnalysis invalid = analyzer.analyze(request(fixture.invalidXml(), type));

            assertTrue(type.name(), valid.isValid());
            assertFalse(type.name(), invalid.isValid());
            assertTrue(type.name(), hasIssue(invalid, fixture.expectedIssue()));
            assertEquals(fixture.expectedRepairability(), invalid.getQualityIssues().stream()
                    .filter(issue -> issue.type() == fixture.expectedIssue())
                    .findFirst()
                    .orElseThrow()
                    .repairability());
            assertEquals(CanvasEvidenceSource.DETERMINISTIC,
                    invalid.getQualityIssues().get(0).evidence().source());
        }
    }

    @Test
    public void typedRequestAppliesProfileAndLegacyCallUsesTypedAdapter() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasAnalysis flowchart = analyzer.analyze(new CanvasAnalysisRequest(
                CROSSING_FLOW, DiagramType.FLOWCHART, LayoutFamily.TOP_DOWN,
                DiagramQualityProfileCatalog.CURRENT_VERSION));
        CanvasAnalysis concept = analyzer.analyze(new CanvasAnalysisRequest(
                CROSSING_FLOW, DiagramType.CONCEPT, LayoutFamily.RADIAL,
                DiagramQualityProfileCatalog.CURRENT_VERSION));
        CanvasAnalysis legacyUnknown = analyzer.analyze(CROSSING_FLOW, "unknown");

        assertTrue(hasIssue(flowchart, CanvasIssueType.EDGE_NODE_CROSSING));
        assertTrue(hasIssue(concept, CanvasIssueType.EDGE_NODE_CROSSING));
        assertFalse("unknown/imported diagrams must use the conservative generic profile",
                hasIssue(legacyUnknown, CanvasIssueType.EDGE_NODE_CROSSING));
        assertEquals(DiagramType.FLOWCHART, flowchart.getDiagramType());
        assertEquals(LayoutFamily.TOP_DOWN, flowchart.getLayoutFamily());
        assertEquals(DiagramQualityProfileCatalog.CURRENT_VERSION, flowchart.getProfileVersion());
        assertEquals(CanvasRepairability.CONDITIONAL_AUTOMATIC,
                flowchart.getQualityIssues().stream()
                        .filter(issue -> issue.type() == CanvasIssueType.EDGE_NODE_CROSSING)
                        .findFirst()
                        .orElseThrow()
                        .repairability());
        assertEquals(CanvasRepairability.MODEL_ASSISTED,
                concept.getQualityIssues().stream()
                        .filter(issue -> issue.type() == CanvasIssueType.EDGE_NODE_CROSSING)
                        .findFirst()
                        .orElseThrow()
                        .repairability());
    }

    @Test
    public void unsupportedProfileVersionFallsBackConsistentlyForValidAndInvalidXml() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();
        CanvasAnalysis valid = analyzer.analyze(new CanvasAnalysisRequest(
                minimalValid(DiagramType.FLOWCHART), DiagramType.FLOWCHART, null, "future-profile"));
        CanvasAnalysis invalid = analyzer.analyze(new CanvasAnalysisRequest(
                "not xml", DiagramType.FLOWCHART, null, "future-profile"));

        assertEquals(DiagramType.GENERIC, valid.getDiagramType());
        assertEquals(DiagramType.GENERIC, invalid.getDiagramType());
        assertEquals(valid.getProfileVersion(), invalid.getProfileVersion());
    }

    private boolean hasIssue(CanvasAnalysis analysis, CanvasIssueType type) {
        return analysis.getIssues().stream().anyMatch(issue -> issue.getType() == type);
    }

    private CanvasAnalysisRequest request(String xml, DiagramType type) {
        return new CanvasAnalysisRequest(xml, type, null, DiagramQualityProfileCatalog.CURRENT_VERSION);
    }

    private ProfileContractFixture fixture(DiagramType type) {
        String valid = minimalValid(type);
        return switch (type) {
            case FLOWCHART, ARCHITECTURE -> new ProfileContractFixture(
                    valid, CROSSING_FLOW, CanvasIssueType.EDGE_NODE_CROSSING,
                    CanvasRepairability.CONDITIONAL_AUTOMATIC);
            case SEQUENCE, STATE -> new ProfileContractFixture(
                    valid, OVERFLOWING_LABEL, CanvasIssueType.TEXT_OVERFLOW,
                    CanvasRepairability.MODEL_ASSISTED);
            case ER, USE_CASE, GENERIC -> new ProfileContractFixture(
                    valid, OVERLAPPING_NODES, CanvasIssueType.NODE_OVERLAP,
                    CanvasRepairability.MODEL_ASSISTED);
            case UML -> new ProfileContractFixture(
                    valid, MISSING_GEOMETRY, CanvasIssueType.MISSING_GEOMETRY,
                    CanvasRepairability.MANUAL_ONLY);
            case CONCEPT -> new ProfileContractFixture(
                    valid, CROSSING_FLOW, CanvasIssueType.EDGE_NODE_CROSSING,
                    CanvasRepairability.MODEL_ASSISTED);
            case ILLUSTRATION -> new ProfileContractFixture(
                    valid, BROKEN_EDGE, CanvasIssueType.BROKEN_EDGE,
                    CanvasRepairability.MANUAL_ONLY);
        };
    }

    private String minimalValid(DiagramType type) {
        return "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='node' value='" + type.name() + "' vertex='1' parent='1'>"
                + "<mxGeometry x='40' y='40' width='140' height='60' as='geometry'/></mxCell>"
                + "</root></mxGraphModel>";
    }

    private record ProfileContractFixture(
            String validXml,
            String invalidXml,
            CanvasIssueType expectedIssue,
            CanvasRepairability expectedRepairability
    ) {
    }
}
