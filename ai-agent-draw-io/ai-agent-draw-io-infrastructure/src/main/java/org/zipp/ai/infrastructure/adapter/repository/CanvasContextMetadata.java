package org.zipp.ai.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derives the small, persisted Canvas projection from the authoritative Draw.io XML.
 *
 * <p>The projection is a cache for routing and diagnostics. Consumers must never use it as
 * a replacement for the XML when they need to edit geometry or inspect exact graph structure.</p>
 */
record CanvasContextMetadata(
        int nodeCount,
        int edgeCount,
        String summary,
        String analysisJson
) {

    static CanvasContextMetadata fromXml(String canvasXml) {
        if (canvasXml == null || canvasXml.isBlank()) {
            return empty();
        }
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(canvasXml));
            int nodes = 0;
            int edges = 0;
            boolean graphModel = false;
            try {
                while (reader.hasNext()) {
                    if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    String name = reader.getLocalName();
                    if ("mxGraphModel".equals(name)) {
                        graphModel = true;
                    } else if ("mxCell".equals(name)) {
                        if ("1".equals(reader.getAttributeValue(null, "vertex"))) {
                            nodes++;
                        }
                        if ("1".equals(reader.getAttributeValue(null, "edge"))) {
                            edges++;
                        }
                    }
                }
            } finally {
                reader.close();
            }
            if (!graphModel) {
                return empty();
            }
            String summary = nodes + edges == 0
                    ? ""
                    : "Canvas contains " + nodes + " nodes and " + edges + " edges.";
            // Keep this projection intentionally small; visual quality analysis is a separate flow.
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("nodeCount", nodes);
            facts.put("edgeCount", edges);
            return new CanvasContextMetadata(
                    nodes, edges, bounded(summary, 2_000), JSON.toJSONString(facts));
        } catch (Exception ignored) {
            // Invalid or unsupported XML cannot become trusted Canvas context or be committed.
            return empty();
        }
    }

    boolean hasElements() {
        return nodeCount > 0 || edgeCount > 0;
    }

    private static CanvasContextMetadata empty() {
        return new CanvasContextMetadata(
                0, 0, "", JSON.toJSONString(Map.of("nodeCount", 0, "edgeCount", 0)));
    }

    private static String bounded(String value, int limit) {
        String normalized = value.trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }
}
