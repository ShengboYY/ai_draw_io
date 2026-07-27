package org.zipp.ai.domain.grounding;

import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.canvas.*;
import org.zipp.ai.domain.agent.service.canvas.CanvasMutationGate;
import org.zipp.ai.domain.citation.model.valobj.*;
import org.zipp.ai.domain.citation.service.CitationGuard;
import org.zipp.ai.domain.grounding.port.GroundedCanvasCommitPort;
import org.zipp.ai.domain.retrieval.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Deep commit seam joining mutation policy, citation policy and one infrastructure transaction. */
public final class CanvasCommitModule {
    private final CanvasMutationGate mutationGate;
    private final CitationGuard citationGuard;
    private final GroundedCanvasCommitPort commitPort;
    private final DirectSourceConflictPolicy directSourceConflictPolicy =
            new DirectSourceConflictPolicy();

    public CanvasCommitModule(CanvasMutationGate mutationGate, CitationGuard citationGuard,
                              GroundedCanvasCommitPort commitPort) {
        this.mutationGate = Objects.requireNonNull(mutationGate, "mutationGate");
        this.citationGuard = Objects.requireNonNull(citationGuard, "citationGuard");
        this.commitPort = Objects.requireNonNull(commitPort, "commitPort");
    }

    public CanvasCommitResult commit(CanvasCommitCommand command, RunResourceDomain resources) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resources, "resources");
        if (!command.manifestValid()) {
            resources.closeExactlyOnce(CloseReason.FAILED);
            return CanvasCommitResult.rejected(command.mutation().currentXml(),
                    List.of("CITATION_MANIFEST_INVALID"));
        }
        CanvasMutationDecision mutation = mutationGate.assess(command.mutation());
        if (!accepted(mutation.status())) {
            resources.closeExactlyOnce(CloseReason.FAILED);
            return CanvasCommitResult.rejected(mutation.resultingXml(), List.of(
                    mutation.rejectionReason() == null ? mutation.status().name() : mutation.rejectionReason().name()));
        }
        if (mutation.changedCellIds().stream().anyMatch(command.immutableCellIds()::contains)) {
            // Retrieved evidence may add new cells, but it cannot rewrite topology copied from
            // the direct source image without an explicit user-confirmed conflict resolution.
            resources.closeExactlyOnce(CloseReason.FAILED);
            return CanvasCommitResult.rejected(
                    mutation.resultingXml(), List.of("DIRECT_SOURCE_CONFLICT"));
        }
        List<String> directConflicts = directSourceConflictPolicy.conflicts(
                mutation, command.immutableCellIds(), command.bindings());
        if (!directConflicts.isEmpty()) {
            resources.closeExactlyOnce(CloseReason.FAILED);
            return CanvasCommitResult.rejected(mutation.resultingXml(), directConflicts);
        }
        Set<String> inheritanceCandidates = inheritedCellIds(mutation, command.bindings());
        Map<String, GroundedCanvasCommitPort.InheritedProvenance> inherited = inheritanceCandidates.isEmpty()
                ? Map.of() : commitPort.findPersistedProvenance(new GroundedCanvasCommitPort.InheritanceQuery(
                        command.mutation().userId(), command.mutation().diagramId(),
                        command.mutation().expectedVersion(), inheritanceCandidates));
        Set<String> inheritedCellIds = inherited.keySet();
        CitationGuardResult guarded = citationGuard.validate(
                mutation.resultingXml(), command.bindings(), command.evidenceAccess(), command.strict(),
                inheritedCellIds);
        if (!guarded.accepted()) {
            resources.closeExactlyOnce(CloseReason.FAILED);
            return CanvasCommitResult.rejected(mutation.resultingXml(), guarded.errors());
        }
        try {
            List<GroundedCanvasCommitPort.CitationWrite> writes = writes(command, guarded.bindings());
            String injected = injectServerMetadata(mutation.resultingXml(), writes, inherited);
            String contentHash = sha256(injected);
            resources.beginCommit();
            CanvasStateSaveResult saved = commitPort.commit(new GroundedCanvasCommitPort.CommitPlan(
                    command.mutation().userId(), command.mutation().diagramId(),
                    command.mutation().expectedVersion(), command.mutation().expectedContentHash(),
                    command.requestId(), command.runId(), injected, contentHash,
                    "AI_EVIDENCE", writes, inheritedCellIds, command.evidenceAccess().runGeneration()));
            resources.closeExactlyOnce(CloseReason.COMMITTED);
            return CanvasCommitResult.committed(injected, saved);
        } catch (RuntimeException failure) {
            resources.closeExactlyOnce(CloseReason.FAILED);
            return CanvasCommitResult.unavailable(
                    mutation.resultingXml(), "CANVAS_COMMIT_FAILED");
        }
    }

    private List<GroundedCanvasCommitPort.CitationWrite> writes(CanvasCommitCommand command,
                                                                List<CitationBinding> bindings) {
        List<GroundedCanvasCommitPort.CitationWrite> writes = new ArrayList<>();
        for (CitationBinding binding : bindings) {
            String provenanceRef = opaque("prv", command.runId() + ":" + binding.cellId());
            String citationId = opaque("cit", command.runId() + ":" + binding.statementKey());
            List<GroundedCanvasCommitPort.EvidenceLink> links = binding.citationKeys().stream()
                    .map(key -> command.evidenceAccess().item(key).map(item ->
                            new GroundedCanvasCommitPort.EvidenceLink(key, item.evidenceId(), item.materialId(),
                                    item.versionId(), item.revisionId(), "SUPPORT",
                                    item.origin())).orElseThrow())
                    .toList();
            writes.add(new GroundedCanvasCommitPort.CitationWrite(citationId, provenanceRef,
                    binding.cellId(), binding.statementKey(), binding.supportType(),
                    sha256(binding.statementText()), links));
        }
        return List.copyOf(writes);
    }

    private Set<String> inheritedCellIds(CanvasMutationDecision mutation,
                                         List<CitationBinding> bindings) {
        if (mutation.before() == null || mutation.after() == null) return Set.of();
        Map<String, CanvasCellData> before = indexCells(mutation.before().getCells());
        Set<String> rebound = bindings.stream().map(CitationBinding::cellId)
                .collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<String> inherited = new LinkedHashSet<>();
        for (CanvasCellData cell : Optional.ofNullable(mutation.after().getCells()).orElse(List.of())) {
            CanvasCellData previous = before.get(cell.getId());
            if (previous != null && !rebound.contains(cell.getId())
                    && semanticHash(previous).equals(semanticHash(cell))) {
                inherited.add(cell.getId());
            }
        }
        return Set.copyOf(inherited);
    }

    private Map<String, CanvasCellData> indexCells(List<CanvasCellData> cells) {
        LinkedHashMap<String, CanvasCellData> indexed = new LinkedHashMap<>();
        if (cells != null) for (CanvasCellData cell : cells) indexed.put(cell.getId(), cell);
        return indexed;
    }

    private String semanticHash(CanvasCellData cell) {
        return sha256(String.join("|", text(cell.getKind()), text(cell.getLabel()),
                text(cell.getParentId()), text(cell.getSource()), text(cell.getTarget())));
    }

    private String text(String value) { return value == null ? "" : value; }

    private String injectServerMetadata(String xml, List<GroundedCanvasCommitPort.CitationWrite> writes,
                                        Map<String, GroundedCanvasCommitPort.InheritedProvenance> inherited) {
        try {
            DocumentBuilderFactory factory = secureFactory();
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Map<String, GroundedCanvasCommitPort.CitationWrite> byCell = new LinkedHashMap<>();
            for (GroundedCanvasCommitPort.CitationWrite write : writes) byCell.putIfAbsent(write.cellId(), write);
            NodeList cells = document.getElementsByTagName("mxCell");
            for (int index = 0; index < cells.getLength(); index++) {
                Element cell = (Element) cells.item(index);
                List<String> remove = new ArrayList<>();
                NamedNodeMap attributes = cell.getAttributes();
                for (int attributeIndex = 0; attributeIndex < attributes.getLength(); attributeIndex++) {
                    String name = attributes.item(attributeIndex).getNodeName();
                    if (name.startsWith("zipp")) remove.add(name);
                }
                remove.forEach(cell::removeAttribute);
                GroundedCanvasCommitPort.CitationWrite write = byCell.get(cell.getAttribute("id"));
                if (write != null) {
                    cell.setAttribute("zippCitationSchema", "1");
                    cell.setAttribute("zippProvenanceRef", write.provenanceRef());
                    cell.setAttribute("zippSupportType", write.supportType().name());
                } else {
                    GroundedCanvasCommitPort.InheritedProvenance metadata = inherited.get(cell.getAttribute("id"));
                    if (metadata != null) {
                        cell.setAttribute("zippCitationSchema", "1");
                        cell.setAttribute("zippProvenanceRef", metadata.provenanceRef());
                        cell.setAttribute("zippSupportType", metadata.supportType().name());
                    }
                }
            }
            TransformerFactory transformers = TransformerFactory.newInstance();
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = transformers.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toString();
        } catch (Exception invalid) {
            throw new IllegalArgumentException("CANVAS_METADATA_INJECTION_FAILED", invalid);
        }
    }


    private DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private boolean accepted(CanvasMutationStatus status) {
        return status == CanvasMutationStatus.ACCEPTED || status == CanvasMutationStatus.ACCEPTED_WITH_NOTES;
    }

    private String opaque(String prefix, String input) { return prefix + "_" + sha256(input).substring(0, 24); }

    private String sha256(String input) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(input).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
