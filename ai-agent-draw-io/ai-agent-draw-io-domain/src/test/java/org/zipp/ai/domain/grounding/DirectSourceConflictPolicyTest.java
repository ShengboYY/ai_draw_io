package org.zipp.ai.domain.grounding;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationDecision;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationStatus;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.citation.model.valobj.CitationBinding;
import org.zipp.ai.domain.citation.model.valobj.StatementKind;
import org.zipp.ai.domain.citation.model.valobj.SupportType;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DirectSourceConflictPolicyTest {
    private static final String DIRECT = """
            <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
            <mxCell id="direct-a" value="Approve" vertex="1" parent="1">
              <mxGeometry x="0" y="0" width="160" height="60" as="geometry"/></mxCell>
            <mxCell id="direct-b" value="Release" vertex="1" parent="1">
              <mxGeometry x="300" y="0" width="160" height="60" as="geometry"/></mxCell>
            <mxCell id="direct-edge" edge="1" source="direct-a" target="direct-b" parent="1">
              <mxGeometry relative="1" as="geometry"/></mxCell>
            </root></mxGraphModel>
            """;
    private final DirectSourceConflictPolicy policy = new DirectSourceConflictPolicy();

    @Test
    void rejectsASecondNodeWithTheSameSemanticLabel() {
        String after = DIRECT.replace("</root>", """
                <mxCell id="retrieved" value=" APPROVE " vertex="1" parent="1">
                  <mxGeometry x="600" y="0" width="160" height="60" as="geometry"/></mxCell>
                </root>""");

        assertEquals(List.of("DIRECT_LABEL_CONFLICT"),
                policy.conflicts(decision(after), Set.of("direct-a", "direct-b", "direct-edge"),
                        List.of(binding("retrieved", StatementKind.NODE_TEXT, "", ""))));
    }

    @Test
    void rejectsANewNodeThatMostlyOccludesAnOriginalNode() {
        String after = DIRECT.replace("</root>", """
                <mxCell id="retrieved" value="Supplement" vertex="1" parent="1">
                  <mxGeometry x="20" y="5" width="160" height="60" as="geometry"/></mxCell>
                </root>""");

        assertEquals(List.of("DIRECT_GEOMETRY_CONFLICT"),
                policy.conflicts(decision(after), Set.of("direct-a", "direct-b", "direct-edge"),
                        List.of(binding("retrieved", StatementKind.NODE_TEXT, "", ""))));
    }

    @Test
    void rejectsASecondRelationshipAcrossTheSameDirectEndpoints() {
        String after = DIRECT.replace("</root>", """
                <mxCell id="retrieved-edge" edge="1"
                  source="direct-a" target="direct-b" parent="1">
                  <mxGeometry relative="1" as="geometry"/></mxCell>
                </root>""");

        assertEquals(List.of("DIRECT_RELATION_CONFLICT"),
                policy.conflicts(decision(after), Set.of("direct-a", "direct-b", "direct-edge"),
                        List.of(binding("retrieved-edge", StatementKind.EDGE_RELATION,
                                "direct-a", "direct-b"))));
    }

    @Test
    void inspectsAnUnboundDecorativeCellThatOccludesTheDirectGraph() {
        String after = DIRECT.replace("</root>", """
                <mxCell id="unbound-overlay" vertex="1" parent="1">
                  <mxGeometry x="10" y="0" width="160" height="60" as="geometry"/></mxCell>
                </root>""");

        assertEquals(List.of("RETRIEVED_CELL_UNBOUND", "DIRECT_GEOMETRY_CONFLICT"),
                policy.conflicts(decision(after), Set.of("direct-a", "direct-b", "direct-edge"),
                        List.of()));
    }

    @Test
    void rejectsAnUnboundDanglingEdge() {
        String after = DIRECT.replace("</root>", """
                <mxCell id="dangling-edge" edge="1" parent="1">
                  <mxGeometry relative="1" as="geometry">
                    <mxPoint x="500" y="200" as="sourcePoint"/>
                    <mxPoint x="600" y="200" as="targetPoint"/>
                  </mxGeometry></mxCell>
                </root>""");

        assertEquals(List.of("RETRIEVED_EDGE_UNBOUND"),
                policy.conflicts(decision(after), Set.of("direct-a", "direct-b", "direct-edge"),
                        List.of()));
    }

    @Test
    void rejectsAnUnboundDecorativeNodeEvenWhenItDoesNotOverlap() {
        String after = DIRECT.replace("</root>", """
                <mxCell id="decoration" vertex="1" parent="1">
                  <mxGeometry x="600" y="200" width="20" height="20" as="geometry"/></mxCell>
                </root>""");

        assertEquals(List.of("RETRIEVED_CELL_UNBOUND"),
                policy.conflicts(decision(after), Set.of("direct-a", "direct-b", "direct-edge"),
                        List.of()));
    }

    @Test
    void allowsADistinctReverseRelationship() {
        String after = DIRECT.replace("</root>", """
                <mxCell id="retrieved-edge" value="acknowledges" edge="1"
                  source="direct-b" target="direct-a" parent="1">
                  <mxGeometry relative="1" as="geometry"/></mxCell>
                </root>""");

        assertEquals(List.of(),
                policy.conflicts(decision(after), Set.of("direct-a", "direct-b", "direct-edge"),
                        List.of(binding("retrieved-edge", StatementKind.EDGE_RELATION,
                                "direct-b", "direct-a"))));
    }

    private CanvasMutationDecision decision(String afterXml) {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();
        return new CanvasMutationDecision(
                CanvasMutationStatus.ACCEPTED, afterXml,
                analyzer.analyze(DIRECT, "flowchart"),
                analyzer.analyze(afterXml, "flowchart"),
                Set.of(), Map.of(), null, null, null);
    }

    private CitationBinding binding(String cellId, StatementKind kind,
                                    String sourceCellId, String targetCellId) {
        return new CitationBinding(cellId, "statement-" + cellId, kind,
                kind == StatementKind.NODE_TEXT ? "Supplement" : "Release -> Approve",
                sourceCellId, targetCellId, List.of("E1"), List.of(), SupportType.EVIDENCE);
    }
}
