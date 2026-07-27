package org.zipp.ai.domain.citation.service;

import org.zipp.ai.domain.citation.model.aggregate.SourceCitation;
import org.zipp.ai.domain.citation.model.entity.CitationEvidence;
import org.zipp.ai.domain.citation.model.valobj.*;
import org.zipp.ai.domain.citation.port.ClaimSupportVerifierPort;
import org.zipp.ai.domain.grounding.EvidenceAccessContext;
import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import org.zipp.ai.domain.retrieval.EvidenceSupportRole;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CitationGuard {

    private final Set<String> allowedEvidenceIds;
    private final ClaimSupportVerifierPort verifier;

    public CitationGuard(Set<String> allowedEvidenceIds) {
        this.allowedEvidenceIds = Set.copyOf(allowedEvidenceIds);
        this.verifier = null;
    }

    public CitationGuard(ClaimSupportVerifierPort verifier) {
        this.allowedEvidenceIds = Set.of();
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public void bind(SourceCitation citation, String evidenceId, String versionId,
                     String revisionId, String citationKey) {
        if (!allowedEvidenceIds.contains(evidenceId)) {
            throw new IllegalArgumentException("evidence was not part of the prepared bundle");
        }
        citation.bindEvidence(new CitationEvidence(evidenceId, versionId, revisionId, citationKey));
    }

    public CitationGuardResult validate(String proposedXml, List<CitationBinding> proposedBindings,
                                        EvidenceAccessContext access, boolean strict) {
        return validate(proposedXml, proposedBindings, access, strict, Set.of());
    }

    /** In strict mode, unchanged cells with durable prior provenance need not be rebound. */
    public CitationGuardResult validate(String proposedXml, List<CitationBinding> proposedBindings,
                                        EvidenceAccessContext access, boolean strict,
                                        Set<String> inheritedCellIds) {
        Objects.requireNonNull(access, "access");
        List<CitationBinding> bindings = List.copyOf(proposedBindings == null ? List.of() : proposedBindings);
        LinkedHashSet<String> errors = new LinkedHashSet<>();
        Map<String, Cell> cells = parseCells(proposedXml, errors);
        Set<String> statementKeys = new HashSet<>();
        Map<String, SupportType> supportByCell = new HashMap<>();
        List<ClaimSupportVerifierPort.Request> semanticRequests = new ArrayList<>();
        for (CitationBinding binding : bindings) {
            if (!statementKeys.add(binding.statementKey())) errors.add("DUPLICATE_STATEMENT_KEY");
            SupportType existingSupport = supportByCell.putIfAbsent(binding.cellId(), binding.supportType());
            if (existingSupport != null && existingSupport != binding.supportType()) {
                errors.add("INCONSISTENT_CELL_SUPPORT_TYPE");
            }
            Cell cell = cells.get(binding.cellId());
            if (cell == null) {
                errors.add("CITATION_CELL_NOT_FOUND");
                continue;
            }
            validateXmlCorrespondence(binding, cell, cells, errors);
            validateSupport(binding, access, strict, semanticRequests, errors);
        }
        if (strict) {
            Set<String> boundCells = bindings.stream()
                    .filter(binding -> binding.supportType() == SupportType.EVIDENCE)
                    .map(CitationBinding::cellId).collect(java.util.stream.Collectors.toSet());
            boundCells.addAll(inheritedCellIds == null ? Set.of() : inheritedCellIds);
            boolean unboundFact = cells.entrySet().stream()
                    .filter(entry -> "NODE".equals(entry.getValue().kind()) || "EDGE".equals(entry.getValue().kind()))
                    .filter(entry -> !normalize(entry.getValue().label()).isBlank())
                    .anyMatch(entry -> !boundCells.contains(entry.getKey()));
            if (unboundFact) errors.add("STRICT_FACTUAL_CELL_UNBOUND");
        }
        if (!errors.isEmpty()) return CitationGuardResult.rejected(List.copyOf(errors));
        validateSemanticSupport(semanticRequests, errors);
        return errors.isEmpty()
                ? CitationGuardResult.accepted(bindings)
                : CitationGuardResult.rejected(List.copyOf(errors));
    }

    private void validateSupport(CitationBinding binding, EvidenceAccessContext access, boolean strict,
                                 List<ClaimSupportVerifierPort.Request> semanticRequests, Set<String> errors) {
        if (binding.supportType() == SupportType.AI_KNOWLEDGE) {
            if (strict || !access.aiKnowledgeAllowed()) errors.add("AI_KNOWLEDGE_FORBIDDEN");
            if (!binding.citationKeys().isEmpty() || !binding.supportAtoms().isEmpty()) {
                errors.add("NON_EVIDENCE_SUPPORT_HAS_CITATION");
            }
            return;
        }
        if (binding.supportType() == SupportType.NONE) {
            if (strict) errors.add("STRICT_NON_EVIDENCE_BINDING");
            if (!binding.citationKeys().isEmpty() || !binding.supportAtoms().isEmpty()) {
                errors.add("NON_EVIDENCE_SUPPORT_HAS_CITATION");
            }
            return;
        }
        if (binding.supportType() != SupportType.EVIDENCE) {
            errors.add("UNSUPPORTED_BINDING_SUPPORT_TYPE");
            return;
        }
        if (binding.citationKeys().isEmpty() || binding.citationKeys().size() > 4) {
            errors.add("INVALID_CITATION_COUNT");
        }
        Set<String> atomKeys = new HashSet<>();
        Map<String, List<String>> anchors = new LinkedHashMap<>();
        for (SupportAtom atom : binding.supportAtoms()) {
            if (!atomKeys.add(atom.atomKey())) errors.add("DUPLICATE_SUPPORT_ATOM_KEY");
            if (!binding.citationKeys().contains(atom.citationKey())) {
                errors.add("SUPPORT_ATOM_KEY_NOT_BOUND");
                continue;
            }
            EvidenceBundleItem item = access.item(atom.citationKey()).orElse(null);
            if (item == null) {
                errors.add("CITATION_KEY_OUTSIDE_BUNDLE");
                continue;
            }
            if (item.supportRole() != EvidenceSupportRole.SUPPORT) {
                errors.add("CONTEXT_ONLY_CITATION_FORBIDDEN");
                continue;
            }
            if (!normalizeDisplay(item.text()).contains(normalizeDisplay(atom.anchorText()))) {
                errors.add("SUPPORT_ATOM_NOT_CONTIGUOUS");
            }
            anchors.computeIfAbsent(atom.citationKey(), ignored -> new ArrayList<>()).add(atom.anchorText());
        }
        for (String key : binding.citationKeys()) {
            Optional<EvidenceBundleItem> item = access.item(key);
            if (item.isEmpty()) errors.add("CITATION_KEY_OUTSIDE_BUNDLE");
            else if (item.get().supportRole() != EvidenceSupportRole.SUPPORT) {
                errors.add("CONTEXT_ONLY_CITATION_FORBIDDEN");
            }
            if (!anchors.containsKey(key)) errors.add("CITATION_KEY_WITHOUT_SUPPORT_ATOM");
        }
        List<String> flattened = anchors.values().stream().flatMap(Collection::stream).toList();
        if (!flattened.isEmpty() && !exact(binding.statementText(), flattened)) {
            semanticRequests.add(new ClaimSupportVerifierPort.Request(
                    access.runId(), binding.statementKey(), binding.statementText(), flattened));
        }
    }

    private void validateSemanticSupport(List<ClaimSupportVerifierPort.Request> requests, Set<String> errors) {
        if (requests.isEmpty()) return;
        if (requests.size() > 8 || verifier == null) {
            errors.add("CLAIM_SUPPORT_VERIFIER_UNAVAILABLE");
            return;
        }
        try {
            Map<String, ClaimSupportVerdict> results = new HashMap<>();
            for (ClaimSupportVerifierPort.Result result : verifier.verify(List.copyOf(requests))) {
                if (result != null && result.statementKey() != null) results.put(result.statementKey(), result.verdict());
            }
            for (ClaimSupportVerifierPort.Request request : requests) {
                if (results.get(request.statementKey()) != ClaimSupportVerdict.ENTAILED) {
                    errors.add("CLAIM_NOT_ENTAILED");
                }
            }
        } catch (RuntimeException unavailable) {
            errors.add("CLAIM_SUPPORT_VERIFIER_UNAVAILABLE");
        }
    }

    private void validateXmlCorrespondence(CitationBinding binding, Cell cell,
                                           Map<String, Cell> cells, Set<String> errors) {
        if (binding.statementKind() == StatementKind.NODE_TEXT) {
            if (!"NODE".equals(cell.kind()) || !covers(cell.label(), binding.statementText())) {
                errors.add("NODE_STATEMENT_MISMATCH");
            }
            return;
        }
        if (!"EDGE".equals(cell.kind())
                || !cell.source().equals(binding.sourceCellId())
                || !cell.target().equals(binding.targetCellId())) {
            errors.add("EDGE_DIRECTION_MISMATCH");
            return;
        }
        Cell source = cells.get(cell.source());
        Cell target = cells.get(cell.target());
        if (source == null || target == null
                || !covers(source.label(), binding.statementText())
                || !covers(target.label(), binding.statementText())
                || (!normalize(cell.label()).isBlank() && !covers(cell.label(), binding.statementText()))) {
            errors.add("EDGE_STATEMENT_MISMATCH");
        }
    }

    private Map<String, Cell> parseCells(String xml, Set<String> errors) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            NodeList nodes = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)))
                    .getElementsByTagName("mxCell");
            Map<String, Cell> cells = new HashMap<>();
            for (int index = 0; index < nodes.getLength(); index++) {
                Element element = (Element) nodes.item(index);
                String kind = "1".equals(element.getAttribute("edge")) ? "EDGE"
                        : "1".equals(element.getAttribute("vertex")) ? "NODE" : "OTHER";
                cells.put(element.getAttribute("id"), new Cell(kind, element.getAttribute("value"),
                        element.getAttribute("source"), element.getAttribute("target")));
            }
            return cells;
        } catch (Exception invalidXml) {
            errors.add("CANVAS_XML_INVALID");
            return Map.of();
        }
    }

    private boolean exact(String statement, List<String> anchors) {
        String normalized = normalizeDisplay(statement);
        return anchors.stream().map(this::normalizeDisplay).anyMatch(normalized::equals);
    }

    private boolean covers(String label, String statement) {
        List<String> required = tokens(label);
        // Single-character node labels (common in diagrams and CJK) have no normal token.
        // Match them only as a complete normalized display term, never as a substring.
        if (required.isEmpty()) return containsDisplayTerm(statement, label);
        Set<String> actual = new HashSet<>(tokens(statement));
        long matches = required.stream().filter(actual::contains).count();
        int minimum = required.size() <= 3 ? required.size() : (int) Math.ceil(required.size() * 0.60);
        return matches >= minimum && exactTerms(label).stream().allMatch(normalize(statement)::contains);
    }

    private boolean containsDisplayTerm(String statement, String label) {
        String term = normalizeDisplay(label);
        String display = normalizeDisplay(statement);
        if (term.isBlank()) return false;
        return Pattern.compile("(^|\\s)" + Pattern.quote(term) + "($|\\s)")
                .matcher(display).find();
    }

    private List<String> tokens(String value) {
        String normalized = normalize(value);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher latin = Pattern.compile("[a-z][a-z0-9_-]{1,}").matcher(normalized);
        Set<String> stops = Set.of("the", "is", "a", "an", "of", "for", "to", "and");
        while (latin.find()) if (!stops.contains(latin.group())) result.add(latin.group());
        String cjk = normalized.replaceAll("[^\\p{IsHan}]", "");
        for (int index = 0; index + 2 <= cjk.length(); index++) result.add(cjk.substring(index, index + 2));
        return List.copyOf(result);
    }

    private List<String> exactTerms(String value) {
        Matcher matcher = Pattern.compile("\\d+(?:\\.\\d+)?(?:%|[a-zA-Z]+)?").matcher(normalize(value));
        List<String> result = new ArrayList<>();
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private String normalizeDisplay(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC)
                .replaceAll("<[^>]+>", " ").replaceAll("[\\p{Punct}\\s]+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) { return normalizeDisplay(value); }

    private record Cell(String kind, String label, String source, String target) { }
}
