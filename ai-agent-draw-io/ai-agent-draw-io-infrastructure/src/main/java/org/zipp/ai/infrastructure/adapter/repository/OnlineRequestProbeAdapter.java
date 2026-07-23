package org.zipp.ai.infrastructure.adapter.repository;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.RequestProbeCommand;
import org.zipp.ai.domain.retrieval.SourceProbe;
import org.zipp.ai.domain.retrieval.port.RequestProbeDataPort;
import org.zipp.ai.infrastructure.dao.retrieval.IOnlineRetrievalMapper;
import org.zipp.ai.infrastructure.dao.retrieval.po.OnlineSourcePO;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

/** Builds the router projection from authoritative MySQL and server canvas state only. */
public final class OnlineRequestProbeAdapter implements RequestProbeDataPort {
    private final IOnlineRetrievalMapper retrieval;
    private final ICanvasStateStore canvases;

    public OnlineRequestProbeAdapter(IOnlineRetrievalMapper retrieval, ICanvasStateStore canvases) {
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
        this.canvases = Objects.requireNonNull(canvases, "canvases");
    }

    @Override
    public SourceProbe probeSources(RequestProbeCommand command) {
        List<OnlineSourcePO> selected = command.selectedVersionIds().isEmpty() ? List.of()
                : retrieval.selectExplicitSources(command.owner().ownerType().name(), command.owner().ownerKey(),
                command.diagramId(), command.conversationId(), command.selectedVersionIds());
        List<OnlineSourcePO> attachments = command.attachmentUploadIds().isEmpty() ? List.of()
                : retrieval.selectConversationAttachmentSources(command.owner().ownerType().name(), command.owner().ownerKey(),
                command.conversationId(), command.attachmentUploadIds());
        List<OnlineSourcePO> automatic = retrieval.selectAutomaticSources(command.owner().ownerType().name(),
                command.owner().ownerKey(), command.diagramId(), command.conversationId(), 20);
        Integer pending = retrieval.countPendingConversationUploads(command.owner().ownerKey(), command.conversationId());
        List<OnlineSourcePO> declared = java.util.stream.Stream.concat(selected.stream(), attachments.stream()).toList();
        return new SourceProbe(declared.size(), declared.stream().map(OnlineSourcePO::getKind).distinct().toList(),
                declared.stream().map(OnlineSourcePO::getState).distinct().toList(),
                pending == null ? 0 : pending,
                automatic.stream().anyMatch(row -> "DIAGRAM".equals(row.getScopeType()) && ready(row)),
                automatic.stream().anyMatch(row -> "CHARTBOOK".equals(row.getScopeType()) && ready(row)),
                automatic.stream().anyMatch(row -> "LIBRARY".equals(row.getScopeType()) && ready(row)),
                automatic.stream().anyMatch(OnlineSourcePO::isPinned),
                java.util.stream.Stream.concat(declared.stream(), automatic.stream()).anyMatch(OnlineSourcePO::isHasVisual),
                (int) declared.stream().filter(row -> "PARTIAL_READY".equals(row.getState())).count(),
                command.sourceMode());
    }

    @Override
    public Optional<ServerCanvasFacts> loadCanvasFacts(CatalogOwner owner, String diagramId,
                                                       List<String> selectedCellIds) {
        return canvases.find(owner.ownerKey(), diagramId).flatMap(state -> {
            try {
                CanvasCounts counts = parse(state.getCurrentXml(), selectedCellIds);
                return Optional.of(new ServerCanvasFacts(counts.nodes, counts.edges,
                        state.getVersion() == null ? 0 : state.getVersion(), state.getContentHash(),
                        counts.selectedKinds.size(), counts.selectedKinds));
            } catch (Exception exception) {
                return Optional.empty();
            }
        });
    }

    private CanvasCounts parse(String xml, List<String> selectedIds) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        NodeList cells = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)))
                .getElementsByTagName("mxCell");
        Set<String> requested = Set.copyOf(selectedIds);
        List<String> selectedKinds = new ArrayList<>();
        int nodes = 0;
        int edges = 0;
        for (int index = 0; index < cells.getLength(); index++) {
            Element cell = (Element) cells.item(index);
            String kind = null;
            if ("1".equals(cell.getAttribute("vertex"))) { nodes++; kind = "NODE"; }
            if ("1".equals(cell.getAttribute("edge"))) { edges++; kind = "EDGE"; }
            if (kind != null && requested.contains(cell.getAttribute("id"))) selectedKinds.add(kind);
        }
        return new CanvasCounts(nodes, edges, List.copyOf(selectedKinds));
    }

    private boolean ready(OnlineSourcePO row) {
        return "READY".equals(row.getState()) || "PARTIAL_READY".equals(row.getState());
    }

    private record CanvasCounts(int nodes, int edges, List<String> selectedKinds) { }
}
