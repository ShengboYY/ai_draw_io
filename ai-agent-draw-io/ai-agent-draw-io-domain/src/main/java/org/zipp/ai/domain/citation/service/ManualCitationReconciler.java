package org.zipp.ai.domain.citation.service;

import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasField;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationDecision;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationStatus;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationCommand;
import org.zipp.ai.domain.citation.port.ManualProvenancePort;
import org.zipp.ai.domain.citation.model.valobj.SupportType;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Style-only edits inherit provenance; semantic edits become explicitly MANUAL. */
public final class ManualCitationReconciler {
    private static final Set<CanvasField> PRESENTATION_FIELDS = Set.of(
            CanvasField.STYLE, CanvasField.GEOMETRY, CanvasField.WAYPOINTS);
    private static final Pattern PROVENANCE_REF = Pattern.compile("prv_[a-f0-9]{24}");
    private final ManualProvenancePort provenancePort;

    public ManualCitationReconciler(ManualProvenancePort provenancePort) {
        this.provenancePort = Objects.requireNonNull(provenancePort, "provenancePort");
    }

    /** Replaces every client-provided zipp* attribute from the authoritative previous projection. */
    public CanvasMutationCommand rebuildServerMetadata(CanvasMutationCommand command,
                                                       CanvasMutationDecision assessment) {
        if (assessment == null || !accepted(assessment.status())) return command;
        long previousVersion = command.expectedVersion() == null ? 0L : command.expectedVersion();
        Map<String, ManualProvenancePort.StoredProvenance> previous = previousVersion == 0L ? Map.of()
                : provenancePort.findProvenance(command.userId(), command.diagramId(), previousVersion);
        Map<String, ManualProvenancePort.StoredProvenance> declared = readMetadata(command.candidateXml());
        Map<String, ManualProvenancePort.StoredProvenance> next = new LinkedHashMap<>();
        if (assessment.after() != null && assessment.after().getCells() != null) {
            for (CanvasCellData cell : assessment.after().getCells()) {
                if (!"node".equalsIgnoreCase(text(cell.getKind()))
                        && !"edge".equalsIgnoreCase(text(cell.getKind()))) continue;
                Set<CanvasField> fields = assessment.changedFields().get(cell.getId());
                if (fields != null && !fields.isEmpty() && !PRESENTATION_FIELDS.containsAll(fields)) {
                    ManualProvenancePort.StoredProvenance imported = previous.containsKey(cell.getId())
                            ? null : declared.get(cell.getId());
                    if (imported != null) {
                        // A same-owner import can recover its citation anchor; foreign refs stay opaque and unavailable.
                        ManualProvenancePort.StoredProvenance authorized = provenancePort.findOwnedProvenance(
                                        command.userId(), imported.provenanceRef())
                                .filter(stored -> semanticMatches(stored.semanticHash(), cell))
                                .orElse(new ManualProvenancePort.StoredProvenance(
                                        imported.provenanceRef(), SupportType.UNATTRIBUTED));
                        next.put(cell.getId(), authorized);
                    } else {
                        next.put(cell.getId(), new ManualProvenancePort.StoredProvenance(
                                provenanceRef(command.diagramId(), previousVersion + 1, cell.getId()), SupportType.MANUAL));
                    }
                } else if (previous.containsKey(cell.getId())) {
                    next.put(cell.getId(), previous.get(cell.getId()));
                }
            }
        }
        String rebuilt = injectMetadata(command.candidateXml(), next);
        return new CanvasMutationCommand(command.purpose(), command.currentXml(), rebuilt,
                command.diagramType(), command.authorization(), command.userId(), command.diagramId(),
                command.expectedVersion(), command.expectedContentHash());
    }

    public void reconcile(CanvasMutationDecision decision) {
        if (decision == null || !accepted(decision.status()) || decision.savedState() == null) return;
        CanvasState saved = decision.savedState();
        long version = saved.getVersion();
        Map<String, CanvasCellData> after = new LinkedHashMap<>();
        if (decision.after() != null && decision.after().getCells() != null) {
            for (CanvasCellData cell : decision.after().getCells()) after.put(cell.getId(), cell);
        }
        Map<String, ManualProvenancePort.StoredProvenance> savedMetadata = readMetadata(saved.getCurrentXml());
        Map<String, ManualProvenancePort.ManualCellProvenance> manual = new LinkedHashMap<>();
        ArrayList<String> removed = new ArrayList<>();
        decision.changedFields().forEach((cellId, fields) -> {
            if (fields.contains(CanvasField.DELETE_CELL)) {
                removed.add(cellId);
            } else if (!PRESENTATION_FIELDS.containsAll(fields)) {
                CanvasCellData cell = after.get(cellId);
                if (cell != null) {
                    ManualProvenancePort.StoredProvenance declared = savedMetadata.getOrDefault(cellId,
                            new ManualProvenancePort.StoredProvenance(
                                    provenanceRef(saved.getDiagramId(), version, cellId), SupportType.MANUAL));
                    ManualProvenancePort.StoredProvenance resolved = declared.supportType() == SupportType.UNATTRIBUTED
                            ? declared : provenancePort.findOwnedProvenance(saved.getUserId(), declared.provenanceRef())
                            .filter(stored -> semanticMatches(stored.semanticHash(), cell)).orElse(declared);
                    manual.put(cellId, new ManualProvenancePort.ManualCellProvenance(
                            resolved.provenanceRef(), semanticHash(cell), resolved.supportType(),
                            resolved.currentCitationId()));
                }
            }
        });
        provenancePort.reconcile(new ManualProvenancePort.ManualReconciliationPlan(
                saved.getUserId(), saved.getDiagramId(), Math.max(0, version - 1), version,
                saved.getContentHash(), saved.getCurrentXml(), manual, removed));
    }

    private boolean accepted(CanvasMutationStatus status) {
        return status == CanvasMutationStatus.ACCEPTED || status == CanvasMutationStatus.ACCEPTED_WITH_NOTES;
    }

    private String semanticHash(CanvasCellData cell) {
        String semantic = String.join("|", text(cell.getKind()), text(cell.getLabel()),
                text(cell.getParentId()), text(cell.getSource()), text(cell.getTarget()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(semantic.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String text(String value) { return value == null ? "" : value; }

    private boolean semanticMatches(String storedHash, CanvasCellData cell) {
        if (storedHash == null || storedHash.isBlank()) return false;
        // Grounded statements hash their visible claim; manual versions hash all semantic canvas fields.
        return storedHash.equals(hash(text(cell.getLabel()))) || storedHash.equals(semanticHash(cell));
    }

    private Map<String, ManualProvenancePort.StoredProvenance> readMetadata(String xml) {
        if (xml == null || xml.isBlank()) return Map.of();
        try {
            Document document = secureFactory().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            LinkedHashMap<String, ManualProvenancePort.StoredProvenance> result = new LinkedHashMap<>();
            NodeList cells = document.getElementsByTagName("mxCell");
            for (int index = 0; index < cells.getLength(); index++) {
                Element cell = (Element) cells.item(index);
                String ref = cell.getAttribute("zippProvenanceRef");
                if (!PROVENANCE_REF.matcher(ref).matches()) continue;
                SupportType type;
                try {
                    type = SupportType.valueOf(cell.getAttribute("zippSupportType"));
                } catch (RuntimeException untrustedType) {
                    type = SupportType.UNATTRIBUTED;
                }
                result.put(cell.getAttribute("id"), new ManualProvenancePort.StoredProvenance(ref, type));
            }
            return Map.copyOf(result);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("MANUAL_CANVAS_METADATA_READ_FAILED", invalid);
        }
    }

    private String injectMetadata(String xml, Map<String, ManualProvenancePort.StoredProvenance> metadata) {
        try {
            DocumentBuilderFactory factory = secureFactory();
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList cells = document.getElementsByTagName("mxCell");
            for (int index = 0; index < cells.getLength(); index++) {
                Element cell = (Element) cells.item(index);
                ArrayList<String> remove = new ArrayList<>();
                NamedNodeMap attributes = cell.getAttributes();
                for (int attributeIndex = 0; attributeIndex < attributes.getLength(); attributeIndex++) {
                    String name = attributes.item(attributeIndex).getNodeName();
                    if (name.startsWith("zipp")) remove.add(name);
                }
                remove.forEach(cell::removeAttribute);
                ManualProvenancePort.StoredProvenance provenance = metadata.get(cell.getAttribute("id"));
                if (provenance != null) {
                    cell.setAttribute("zippCitationSchema", "1");
                    cell.setAttribute("zippProvenanceRef", provenance.provenanceRef());
                    cell.setAttribute("zippSupportType", provenance.supportType().name());
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
            throw new IllegalArgumentException("MANUAL_CANVAS_METADATA_REBUILD_FAILED", invalid);
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

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String provenanceRef(String diagramId, long version, String cellId) {
        return "prv_" + hash(diagramId + "|" + version + "|" + cellId).substring(0, 24);
    }
}
