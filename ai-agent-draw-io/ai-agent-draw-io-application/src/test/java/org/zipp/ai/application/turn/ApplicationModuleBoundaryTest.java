package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationModuleBoundaryTest {

    private static final String APPLICATION = "ai-agent-draw-io-application";
    private static final Set<String> REACTOR_MODULES = Set.of(
            "ai-agent-draw-io-api",
            "ai-agent-draw-io-types",
            "ai-agent-draw-io-domain",
            APPLICATION,
            "ai-agent-draw-io-infrastructure",
            "ai-agent-draw-io-trigger",
            "ai-agent-draw-io-ingestion-worker",
            "ai-agent-draw-io-app");

    @Test
    void applicationPomDependsOnlyOnInwardModules() throws Exception {
        Map<String, Set<String>> graph = reactorDependencies();

        assertEquals(Set.of("ai-agent-draw-io-domain", "ai-agent-draw-io-types"),
                graph.get(APPLICATION));
        assertTrue(graph.get("ai-agent-draw-io-api").stream().noneMatch(REACTOR_MODULES::contains));
        assertTrue(graph.get("ai-agent-draw-io-types").stream().noneMatch(REACTOR_MODULES::contains));
        assertTrue(graph.get("ai-agent-draw-io-domain").stream().noneMatch(
                dependency -> dependency.equals(APPLICATION)
                        || dependency.equals("ai-agent-draw-io-infrastructure")
                        || dependency.equals("ai-agent-draw-io-trigger")
                        || dependency.equals("ai-agent-draw-io-app")));
    }

    @Test
    void reactorDependenciesFollowTheModuleDirectionAndContainNoCycle() throws Exception {
        Map<String, Set<String>> graph = reactorDependencies();
        Map<String, Set<String>> allowed = Map.of(
                "ai-agent-draw-io-api", Set.of(),
                "ai-agent-draw-io-types", Set.of(),
                "ai-agent-draw-io-domain", Set.of("ai-agent-draw-io-types"),
                APPLICATION, Set.of("ai-agent-draw-io-domain", "ai-agent-draw-io-types"),
                "ai-agent-draw-io-infrastructure", Set.of(
                        "ai-agent-draw-io-domain", APPLICATION),
                "ai-agent-draw-io-trigger", Set.of(
                        "ai-agent-draw-io-api", "ai-agent-draw-io-types",
                        "ai-agent-draw-io-domain", APPLICATION),
                "ai-agent-draw-io-ingestion-worker", Set.of(
                        "ai-agent-draw-io-domain", "ai-agent-draw-io-infrastructure"),
                "ai-agent-draw-io-app", Set.of(
                        "ai-agent-draw-io-trigger", "ai-agent-draw-io-infrastructure", APPLICATION));

        assertEquals(REACTOR_MODULES, graph.keySet());
        for (String module : REACTOR_MODULES) {
            assertTrue(allowed.get(module).containsAll(graph.get(module)),
                    () -> module + " has an illegal inward dependency: " + graph.get(module));
        }
        assertAcyclic(graph);
    }

    private Map<String, Set<String>> reactorDependencies() throws Exception {
        Path root = reactorRoot();
        Map<String, Path> poms = new LinkedHashMap<>();
        try (var children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .map(directory -> directory.resolve("pom.xml"))
                    .filter(Files::isRegularFile)
                    .forEach(pom -> {
                        try {
                            String artifactId = directText(parse(pom).getDocumentElement(), "artifactId");
                            if (REACTOR_MODULES.contains(artifactId)) {
                                poms.put(artifactId, pom);
                            }
                        } catch (Exception exception) {
                            throw new IllegalStateException("cannot read module POM " + pom, exception);
                        }
                    });
        }

        Map<String, Set<String>> graph = new HashMap<>();
        for (Map.Entry<String, Path> entry : poms.entrySet()) {
            Document document = parse(entry.getValue());
            Element project = document.getDocumentElement();
            Set<String> dependencies = new HashSet<>();
            for (Element dependenciesElement : directChildren(project, "dependencies")) {
                for (Element dependency : directChildren(dependenciesElement, "dependency")) {
                    String scope = directTextOrEmpty(dependency, "scope");
                    String artifactId = directTextOrEmpty(dependency, "artifactId");
                    if (!"test".equals(scope) && REACTOR_MODULES.contains(artifactId)) {
                        dependencies.add(artifactId);
                    }
                }
            }
            graph.put(entry.getKey(), Set.copyOf(dependencies));
        }
        return graph;
    }

    private void assertAcyclic(Map<String, Set<String>> graph) {
        Map<String, VisitState> states = new HashMap<>();
        for (String module : graph.keySet()) {
            visit(module, graph, states, new ArrayList<>());
        }
    }

    private void visit(String module, Map<String, Set<String>> graph,
                       Map<String, VisitState> states, List<String> path) {
        VisitState state = states.get(module);
        if (state == VisitState.VISITING) {
            throw new AssertionError("Maven module dependency cycle: " + path + " -> " + module);
        }
        if (state == VisitState.VISITED) {
            return;
        }
        states.put(module, VisitState.VISITING);
        path.add(module);
        for (String dependency : graph.getOrDefault(module, Set.of())) {
            visit(dependency, graph, states, path);
        }
        path.remove(path.size() - 1);
        states.put(module, VisitState.VISITED);
    }

    private Path reactorRoot() {
        Path candidate = Path.of("pom.xml").toAbsolutePath().normalize().getParent();
        if (!Files.exists(candidate.resolve("ai-agent-draw-io-app/pom.xml"))) {
            candidate = candidate.getParent();
        }
        return candidate;
    }

    private Document parse(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(pom.toFile());
    }

    private List<Element> directChildren(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && name.equals(element.getTagName())) {
                children.add(element);
            }
        }
        return children;
    }

    private String directText(Element parent, String name) {
        String value = directTextOrEmpty(parent, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("missing " + name);
        }
        return value;
    }

    private String directTextOrEmpty(Element parent, String name) {
        List<Element> children = directChildren(parent, name);
        return children.isEmpty() ? "" : children.get(0).getTextContent().trim();
    }

    private enum VisitState { VISITING, VISITED }
}
