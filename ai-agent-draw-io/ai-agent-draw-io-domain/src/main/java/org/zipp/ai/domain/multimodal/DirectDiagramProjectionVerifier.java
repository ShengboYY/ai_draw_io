package org.zipp.ai.domain.multimodal;

import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares a candidate with the one canonical safe projection of the verified graph.
 * Exact comparison also rejects extra attributes, active content and layout drift.
 */
final class DirectDiagramProjectionVerifier {
    private final DefaultImageToDiagramModule canonicalProjection =
            new DefaultImageToDiagramModule();

    DirectDiagramProjectionVerification verify(ObservedDiagramGraph graph,
                                               String xml,
                                               List<String> declaredCellIds) {
        List<DirectDiagramProjectionVerification.Issue> issues = new ArrayList<>();
        if (!parseable(xml)) {
            return new DirectDiagramProjectionVerification(
                    List.of(DirectDiagramProjectionVerification.Issue.MALFORMED_XML));
        }
        ImageToDiagramOutcome.Converted expected =
                canonicalProjection.projectVerified(graph);
        if (!expected.cellIds().equals(declaredCellIds)) {
            issues.add(DirectDiagramProjectionVerification.Issue.CELL_MANIFEST_MISMATCH);
        }
        if (!expected.mxGraphModelXml().equals(xml)) {
            issues.add(DirectDiagramProjectionVerification.Issue.PROJECTION_MISMATCH);
        }
        return new DirectDiagramProjectionVerification(issues);
    }

    private boolean parseable(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }
}
