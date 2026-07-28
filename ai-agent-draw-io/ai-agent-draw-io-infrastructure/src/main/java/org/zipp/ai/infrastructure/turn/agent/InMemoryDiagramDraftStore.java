package org.zipp.ai.infrastructure.turn.agent;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.agent.DiagramDraftSnapshot;
import org.zipp.ai.application.turn.agent.DiagramDraftStore;
import org.zipp.ai.application.turn.agent.DraftCellMutation;
import org.zipp.ai.application.turn.agent.DraftMutationType;
import org.zipp.ai.application.turn.agent.DraftPatchResult;
import org.zipp.ai.application.turn.agent.DraftRef;
import org.zipp.ai.domain.agent.service.canvas.CanvasXmlContentHasher;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-process attempt workspace with immutable versions and strict ID-scoped DOM patches. */
@Component
public final class InMemoryDiagramDraftStore implements DiagramDraftStore {

    private static final int MAX_CANVAS_XML_LENGTH = 4_000_000;
    private static final int MAX_DRAFT_VERSIONS = 8;
    private static final int MAX_PATCH_MUTATIONS = 64;

    private final Map<AttemptKey, AttemptDrafts> attempts = new ConcurrentHashMap<>();
    private final CanvasXmlContentHasher hasher = new CanvasXmlContentHasher();

    @Override
    public DiagramDraftSnapshot create(FencedAttempt attempt, String canvasXml) {
        AttemptKey key = key(attempt);
        AttemptDrafts drafts = attempts.computeIfAbsent(key, ignored -> new AttemptDrafts());
        synchronized (drafts) {
            if (!drafts.versions.isEmpty()) {
                throw new IllegalStateException("DRAFT_ALREADY_INITIALIZED");
            }
            String normalized = validateAndSerialize(parseGraphModel(canvasXml));
            return save(drafts, normalized);
        }
    }

    @Override
    public DiagramDraftSnapshot read(FencedAttempt attempt, DraftRef ref) {
        AttemptDrafts drafts = ownedDrafts(attempt);
        synchronized (drafts) {
            DiagramDraftSnapshot snapshot = drafts.versions.get(ref);
            if (snapshot == null) {
                throw new IllegalStateException("DRAFT_NOT_FOUND");
            }
            return snapshot;
        }
    }

    @Override
    public DraftPatchResult patch(
            FencedAttempt attempt,
            DraftRef ref,
            String expectedDigest,
            List<DraftCellMutation> mutations
    ) {
        AttemptDrafts drafts = ownedDrafts(attempt);
        List<DraftCellMutation> requested = List.copyOf(mutations == null ? List.of() : mutations);
        if (requested.isEmpty() || requested.size() > MAX_PATCH_MUTATIONS) {
            throw new IllegalArgumentException("DRAFT_PATCH_SIZE_INVALID");
        }
        synchronized (drafts) {
            DiagramDraftSnapshot before = drafts.versions.get(ref);
            if (before == null) {
                throw new IllegalStateException("DRAFT_NOT_FOUND");
            }
            if (!before.digest().equals(expectedDigest)) {
                throw new IllegalStateException("DRAFT_DIGEST_MISMATCH");
            }
            if (drafts.versions.size() >= MAX_DRAFT_VERSIONS) {
                throw new IllegalStateException("DRAFT_VERSION_LIMIT_EXCEEDED");
            }

            Document document = parseGraphModel(before.canvasXml());
            Map<String, Element> cells = cellsById(document);
            Set<String> declared = new LinkedHashSet<>();
            for (DraftCellMutation mutation : requested) {
                if (!declared.add(mutation.cellId())) {
                    throw new IllegalArgumentException("DRAFT_PATCH_DUPLICATE_CELL");
                }
                apply(document, cells, mutation);
                cells = cellsById(document);
            }

            String normalized = validateAndSerialize(document);
            DiagramDraftSnapshot after = save(drafts, normalized);
            return new DraftPatchResult(after, List.copyOf(declared));
        }
    }

    @Override
    public void discard(FencedAttempt attempt) {
        attempts.remove(key(attempt));
    }

    private void apply(
            Document document,
            Map<String, Element> cells,
            DraftCellMutation mutation
    ) {
        Element existing = cells.get(mutation.cellId());
        if (mutation.type() == DraftMutationType.DELETE) {
            if (existing == null || "0".equals(mutation.cellId()) || "1".equals(mutation.cellId())) {
                throw new IllegalArgumentException("DRAFT_DELETE_TARGET_INVALID");
            }
            existing.getParentNode().removeChild(existing);
            return;
        }

        Element replacement = parseCell(mutation.cellXml());
        if (!mutation.cellId().equals(replacement.getAttribute("id"))) {
            throw new IllegalArgumentException("DRAFT_CELL_ID_MISMATCH");
        }
        Node imported = document.importNode(replacement, true);
        if (mutation.type() == DraftMutationType.ADD) {
            if (existing != null) {
                throw new IllegalArgumentException("DRAFT_ADD_TARGET_EXISTS");
            }
            graphRoot(document).appendChild(imported);
            return;
        }
        if (existing == null || "0".equals(mutation.cellId()) || "1".equals(mutation.cellId())) {
            throw new IllegalArgumentException("DRAFT_REPLACE_TARGET_INVALID");
        }
        existing.getParentNode().replaceChild(imported, existing);
    }

    private DiagramDraftSnapshot save(AttemptDrafts drafts, String canvasXml) {
        DraftRef ref = new DraftRef("draft-" + UUID.randomUUID());
        DiagramDraftSnapshot snapshot = new DiagramDraftSnapshot(
                ref,
                hasher.hash(canvasXml),
                canvasXml,
                drafts.versions.size() + 1);
        drafts.versions.put(ref, snapshot);
        return snapshot;
    }

    private String validateAndSerialize(Document document) {
        Map<String, Element> cells = cellsById(document);
        if (!cells.containsKey("0") || !cells.containsKey("1")) {
            throw new IllegalArgumentException("DRAFT_ROOT_CELLS_REQUIRED");
        }
        for (Element cell : cells.values()) {
            validateReference(cells, cell, "parent");
            validateReference(cells, cell, "source");
            validateReference(cells, cell, "target");
        }
        return serialize(document);
    }

    private void validateReference(Map<String, Element> cells, Element cell, String attribute) {
        String reference = cell.getAttribute(attribute);
        if (!reference.isBlank() && !cells.containsKey(reference)) {
            throw new IllegalArgumentException("DRAFT_REFERENCE_MISSING");
        }
    }

    private Map<String, Element> cellsById(Document document) {
        Map<String, Element> cells = new LinkedHashMap<>();
        NodeList nodes = document.getElementsByTagName("mxCell");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element cell = (Element) nodes.item(index);
            String id = cell.getAttribute("id").trim();
            if (id.isBlank() || id.length() > 255
                    || id.chars().anyMatch(Character::isISOControl)
                    || cells.putIfAbsent(id, cell) != null) {
                throw new IllegalArgumentException("DRAFT_CELL_ID_INVALID");
            }
        }
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("DRAFT_HAS_NO_CELLS");
        }
        return cells;
    }

    private Document parseGraphModel(String xml) {
        String source = boundedXml(xml);
        try {
            Document document = documentBuilderFactory().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(source)));
            if (document.getDocumentElement() == null
                    || !"mxGraphModel".equals(document.getDocumentElement().getTagName())) {
                throw new IllegalArgumentException("DRAFT_GRAPH_MODEL_REQUIRED");
            }
            graphRoot(document);
            return document;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("DRAFT_XML_INVALID", exception);
        }
    }

    private Element parseCell(String xml) {
        String source = boundedXml(xml);
        try {
            Document document = documentBuilderFactory().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(source)));
            Element element = document.getDocumentElement();
            if (element == null || !"mxCell".equals(element.getTagName())) {
                throw new IllegalArgumentException("DRAFT_CELL_XML_REQUIRED");
            }
            return element;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("DRAFT_CELL_XML_INVALID", exception);
        }
    }

    private Element graphRoot(Document document) {
        NodeList roots = document.getDocumentElement().getElementsByTagName("root");
        if (roots.getLength() != 1) {
            throw new IllegalArgumentException("DRAFT_ROOT_REQUIRED");
        }
        return (Element) roots.item(0);
    }

    private String boundedXml(String xml) {
        String source = xml == null ? "" : xml.trim();
        if (source.isBlank() || source.length() > MAX_CANVAS_XML_LENGTH
                || source.contains("<!DOCTYPE") || source.contains("<!ENTITY")) {
            throw new IllegalArgumentException("DRAFT_XML_BOUNDS_INVALID");
        }
        return source;
    }

    private DocumentBuilderFactory documentBuilderFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory;
    }

    private String serialize(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toString().trim();
        } catch (Exception exception) {
            throw new IllegalArgumentException("DRAFT_XML_SERIALIZATION_FAILED", exception);
        }
    }

    private AttemptDrafts ownedDrafts(FencedAttempt attempt) {
        AttemptDrafts drafts = attempts.get(key(attempt));
        if (drafts == null) {
            throw new IllegalStateException("DRAFT_ATTEMPT_NOT_FOUND");
        }
        return drafts;
    }

    private AttemptKey key(FencedAttempt attempt) {
        if (attempt == null) {
            throw new IllegalArgumentException("fenced attempt is required");
        }
        return new AttemptKey(attempt.key(), attempt.attemptId(), attempt.attemptEpoch());
    }

    private record AttemptKey(TurnKey turnKey, String attemptId, long attemptEpoch) {
    }

    private static final class AttemptDrafts {
        private final Map<DraftRef, DiagramDraftSnapshot> versions = new HashMap<>();
    }
}
