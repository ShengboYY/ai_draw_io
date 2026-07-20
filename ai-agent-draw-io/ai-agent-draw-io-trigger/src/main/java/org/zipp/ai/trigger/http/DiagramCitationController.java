package org.zipp.ai.trigger.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.dto.CellCitationDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.citation.model.valobj.CellCitationView;
import org.zipp.ai.domain.citation.service.CitationQueryService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/diagrams/{diagramId}/cells/{cellId}/citations")
@ConditionalOnProperty(name = {"app.material-rag.enabled", "app.material-lifecycle.enabled"}, havingValue = "true")
public class DiagramCitationController {
    private final CurrentOwnerHttpResolver ownerResolver;
    private final CitationQueryService citations;

    public DiagramCitationController(CurrentOwnerHttpResolver ownerResolver, CitationQueryService citations) {
        this.ownerResolver = ownerResolver;
        this.citations = citations;
    }

    @GetMapping
    public Response<List<CellCitationDTO>> find(@PathVariable String diagramId,
                                                @PathVariable String cellId,
                                                @RequestParam(required = false) Long canvasVersion,
                                                @RequestParam(required = false) String provenanceRef) {
        return CatalogControllerSupport.execute(() -> citations.find(
                        CatalogControllerSupport.requiredOwner(ownerResolver).ownerKey(),
                        diagramId, cellId, canvasVersion, provenanceRef).stream().map(this::dto).toList());
    }

    private CellCitationDTO dto(CellCitationView source) {
        return new CellCitationDTO(source.citationId(), source.cellId(), source.provenanceRef(), source.statementKey(),
                source.supportType().name(), source.state(), source.sources().stream().map(item ->
                new CellCitationDTO.SourceDTO(item.citationKey(), item.materialId(), item.displayName(),
                        item.versionId(), item.versionNo(), item.pageNumber(), item.modality(),
                        item.sourceState(), item.processingRevisionId(), item.bboxJson(), item.origin(),
                        item.excerptAvailable(), item.previewAvailable(), item.boundedExcerpt(),
                        item.previewUrl(), item.deletedAt())).toList());
    }
}
