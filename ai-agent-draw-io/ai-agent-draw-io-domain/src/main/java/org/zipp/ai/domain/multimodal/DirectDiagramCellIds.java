package org.zipp.ai.domain.multimodal;

/** One source of truth for deterministic direct-conversion cell identities. */
final class DirectDiagramCellIds {
    private DirectDiagramCellIds() {}

    static String node(String id) { return "direct-node-" + id; }
    static String edge(String id) { return "direct-edge-" + id; }
    static String group(String id) { return "direct-group-" + id; }
}
