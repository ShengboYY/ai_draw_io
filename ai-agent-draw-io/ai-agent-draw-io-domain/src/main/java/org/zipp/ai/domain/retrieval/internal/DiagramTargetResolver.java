package org.zipp.ai.domain.retrieval.internal;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.zipp.ai.domain.retrieval.TargetCandidate;
import org.zipp.ai.domain.retrieval.ValidatedSelection;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deterministic target lookup over a trusted server snapshot. */
final class DiagramTargetResolver {
    TargetResult resolve(String xml, ValidatedSelection selection, String userMessage) {
        try {
            Map<String, Cell> cells = parse(xml);
            if (!selection.cellIds().isEmpty()) {
                List<Cell> selected = selection.cellIds().stream().map(cells::get).filter(java.util.Objects::nonNull).toList();
                if (selected.size() == selection.cellIds().size()) return new TargetResult.Resolved(selected);
                return new TargetResult.Missing("STALE_CANVAS_SELECTION");
            }
            List<Cell> matches = lexicalMatches(cells.values(), userMessage);
            if (matches.size() == 1) return new TargetResult.Resolved(matches);
            if (matches.size() > 1) return new TargetResult.Ambiguous(matches.stream().limit(8)
                    .map(cell -> new TargetCandidate(cell.id(), cell.kind(), shortLabel(cell.label()), "LABEL_MATCH"))
                    .toList());
            return new TargetResult.Missing("TARGET_NOT_FOUND");
        } catch (Exception exception) {
            return new TargetResult.Missing("CANVAS_PARSE_FAILED");
        }
    }

    private Map<String, Cell> parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        NodeList nodes = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)))
                .getElementsByTagName("mxCell");
        Map<String, Cell> result = new HashMap<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            String kind = "1".equals(element.getAttribute("edge")) ? "EDGE"
                    : "1".equals(element.getAttribute("vertex")) ? "NODE" : "OTHER";
            if (!"OTHER".equals(kind)) {
                result.put(element.getAttribute("id"), new Cell(element.getAttribute("id"), kind,
                        element.getAttribute("value")));
            }
        }
        return result;
    }

    private List<Cell> lexicalMatches(java.util.Collection<Cell> cells, String message) {
        String normalized = normalize(message);
        if (normalized.isBlank()) return List.of();
        List<Cell> matches = new ArrayList<>();
        for (Cell cell : cells) {
            String label = normalize(cell.label());
            if (!label.isBlank() && normalized.contains(label)) matches.add(cell);
        }
        return matches;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("<[^>]+>", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private String shortLabel(String label) {
        String plain = label == null ? "" : label.replaceAll("<[^>]+>", " ").trim();
        return plain.length() <= 80 ? plain : plain.substring(0, 80);
    }

    record Cell(String id, String kind, String label) { }

    sealed interface TargetResult permits TargetResult.Resolved, TargetResult.Ambiguous, TargetResult.Missing {
        record Resolved(List<Cell> cells) implements TargetResult { }
        record Ambiguous(List<TargetCandidate> candidates) implements TargetResult { }
        record Missing(String errorCode) implements TargetResult { }
    }
}
