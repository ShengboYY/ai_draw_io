package org.zipp.ai.domain.agent.service.canvas;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CanvasXmlContentHasher {

    private static final String HASH_PREFIX = "sha256:";

    public String hash(String xml) {
        return HASH_PREFIX + sha256Hex(canonicalize(xml));
    }

    String canonicalize(String xml) {
        String source = xml == null ? "" : xml.trim();
        if (source.isEmpty()) {
            return "";
        }
        try {
            DocumentBuilderFactory factory = secureDocumentBuilderFactory();
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(source)));
            return canonicalNode(document.getDocumentElement());
        } catch (Exception ignored) {
            // Invalid legacy rows still need a stable identity; fall back to trimmed raw XML.
            return source;
        }
    }

    private DocumentBuilderFactory secureDocumentBuilderFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setCoalescing(true);
        factory.setIgnoringComments(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory;
    }

    private String canonicalNode(Node node) {
        if (node == null) {
            return "";
        }
        return switch (node.getNodeType()) {
            case Node.DOCUMENT_NODE -> canonicalChildren(node);
            case Node.ELEMENT_NODE -> canonicalElement(node);
            case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> canonicalText(node.getTextContent());
            default -> "";
        };
    }

    private String canonicalElement(Node node) {
        StringBuilder builder = new StringBuilder();
        builder.append('<').append(node.getNodeName());
        for (Node attribute : sortedAttributes(node.getAttributes())) {
            builder.append(' ')
                    .append(attribute.getNodeName())
                    .append("=\"")
                    .append(escapeAttribute(attribute.getNodeValue()))
                    .append('"');
        }
        builder.append('>');
        builder.append(canonicalChildren(node));
        builder.append("</").append(node.getNodeName()).append('>');
        return builder.toString();
    }

    private String canonicalChildren(Node node) {
        StringBuilder builder = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            builder.append(canonicalNode(children.item(index)));
        }
        return builder.toString();
    }

    private List<Node> sortedAttributes(NamedNodeMap attributes) {
        List<Node> nodes = new ArrayList<>();
        if (attributes == null) {
            return nodes;
        }
        for (int index = 0; index < attributes.getLength(); index++) {
            nodes.add(attributes.item(index));
        }
        nodes.sort(Comparator.comparing(Node::getNodeName));
        return nodes;
    }

    private String canonicalText(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.isEmpty() ? "" : escapeText(trimmed);
    }

    private String escapeText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;");
    }

    private String escapeAttribute(String value) {
        return escapeText(value == null ? "" : value).replace("\"", "&quot;");
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
