package org.zipp.ai.domain.agent.service.evaluation;

import org.apache.commons.lang3.StringUtils;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic draw.io graph projection using exact labels and an explicitly versioned alias map. */
public class DrawioGraphNormalizer {

    public Graph normalize(String xml, Map<String, String> aliases) {
        try {
            Element root = DocumentHelper.parseText(xml).getRootElement().element("root");
            Map<String, String> canonicalAliases = canonicalAliases(aliases);
            Map<String, String> nodesById = new LinkedHashMap<>();
            Map<String, Integer> nodeCounts = new LinkedHashMap<>();
            List<Element> edgeCells = new ArrayList<>();
            for (Object item : root.elements("mxCell")) {
                Element cell = (Element) item;
                if ("1".equals(cell.attributeValue("vertex"))) {
                    String label = canonical(cell.attributeValue("value"), canonicalAliases);
                    nodesById.put(cell.attributeValue("id"), label);
                    nodeCounts.merge(label, 1, Integer::sum);
                } else if ("1".equals(cell.attributeValue("edge"))) {
                    edgeCells.add(cell);
                }
            }
            Set<Edge> edges = new LinkedHashSet<>();
            for (Element edge : edgeCells) {
                edges.add(new Edge(nodesById.get(edge.attributeValue("source")), nodesById.get(edge.attributeValue("target")),
                        canonical(edge.attributeValue("value"), canonicalAliases)));
            }
            return new Graph(new LinkedHashSet<>(nodesById.values()), edges, nodeCounts);
        } catch (Exception e) {
            return new Graph(Set.of(), Set.of(), Map.of());
        }
    }

    public String canonical(String label, Map<String, String> aliases) {
        String normalized = normalizeLabel(label);
        return aliases == null ? normalized : aliases.getOrDefault(normalized, normalized);
    }

    private Map<String, String> canonicalAliases(Map<String, String> aliases) {
        Map<String, String> result = new LinkedHashMap<>();
        if (aliases != null) aliases.forEach((key, value) -> result.put(normalizeLabel(key), normalizeLabel(value)));
        return result;
    }

    private String normalizeLabel(String label) {
        String withoutTags = StringUtils.defaultString(label).replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&");
        return withoutTags.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    public record Graph(Set<String> nodes, Set<Edge> edges, Map<String, Integer> nodeCounts) { }
    public record Edge(String source, String target, String label) { }
}
