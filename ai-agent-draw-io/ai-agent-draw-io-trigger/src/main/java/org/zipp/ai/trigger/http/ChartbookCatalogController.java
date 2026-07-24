package org.zipp.ai.trigger.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.zipp.ai.api.dto.*;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookView;
import org.zipp.ai.domain.chartbook.model.valobj.AddChartbookFileCommand;
import org.zipp.ai.domain.chartbook.model.valobj.CreateChartbookCommand;
import org.zipp.ai.domain.chartbook.service.ChartbookCatalogService;
import org.zipp.ai.domain.chartbook.service.ChartbookFileModule;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name = "app.material-catalog.enabled", havingValue = "true")
public class ChartbookCatalogController {
    private final CurrentOwnerHttpResolver ownerResolver;
    private final ChartbookCatalogService chartbooks;
    private final ChartbookFileModule files;

    public ChartbookCatalogController(CurrentOwnerHttpResolver ownerResolver,
                                      ChartbookCatalogService chartbooks,
                                      ChartbookFileModule files) {
        this.ownerResolver = ownerResolver;
        this.chartbooks = chartbooks;
        this.files = files;
    }

    @PostMapping("/chartbooks")
    public Response<ChartbookResponseDTO> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ChartbookRequestDTO body) {
        return CatalogControllerSupport.execute(() -> view(chartbooks.create(new CreateChartbookCommand(
                owner(), idempotencyKey, body.name()))));
    }

    @GetMapping("/chartbooks")
    public Response<List<ChartbookResponseDTO>> list() {
        return CatalogControllerSupport.execute(() -> chartbooks.findAll(owner()).stream()
                .map(this::view).toList());
    }

    @GetMapping("/chartbooks/{chartbookId}")
    public Response<ChartbookResponseDTO> details(@PathVariable String chartbookId) {
        return CatalogControllerSupport.execute(() -> view(chartbooks.find(owner(), chartbookId)));
    }

    @PatchMapping("/chartbooks/{chartbookId}")
    public Response<ChartbookResponseDTO> rename(@PathVariable String chartbookId,
                                                 @RequestBody ChartbookRequestDTO body) {
        return CatalogControllerSupport.execute(() -> view(chartbooks.rename(
                owner(), chartbookId, body.name())));
    }

    @DeleteMapping("/chartbooks/{chartbookId}")
    public Response<Void> archive(@PathVariable String chartbookId) {
        return CatalogControllerSupport.execute(() -> {
            chartbooks.archive(owner(), chartbookId);
            return null;
        });
    }

    @PostMapping("/chartbooks/{chartbookId}/materials")
    public Response<ChartbookResponseDTO> addMaterial(@PathVariable String chartbookId,
                                                      @RequestBody ChartbookMaterialRequestDTO body) {
        return CatalogControllerSupport.execute(() -> view(chartbooks.addMaterial(
                owner(), chartbookId, body.materialId())));
    }

    @PostMapping("/chartbooks/{chartbookId}/files/{materialId}")
    public Response<MaterialCatalogDetailsDTO> addFile(
            @PathVariable String chartbookId,
            @PathVariable String materialId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return CatalogControllerSupport.execute(() -> MaterialCatalogDtoMapper.details(files.add(
                new AddChartbookFileCommand(owner(), chartbookId, materialId, idempotencyKey)).file()));
    }

    @DeleteMapping("/chartbooks/{chartbookId}/materials/{materialId}")
    public Response<ChartbookResponseDTO> removeMaterial(@PathVariable String chartbookId,
                                                         @PathVariable String materialId) {
        return CatalogControllerSupport.execute(() -> view(chartbooks.removeMaterial(
                owner(), chartbookId, materialId)));
    }

    @PutMapping("/diagrams/{diagramId}/chartbook")
    public Response<ChartbookResponseDTO> assignDiagram(@PathVariable String diagramId,
                                                        @RequestBody DiagramChartbookRequestDTO body) {
        return CatalogControllerSupport.execute(() -> view(chartbooks.assignDiagram(
                owner(), diagramId, body.chartbookId())));
    }

    @DeleteMapping("/diagrams/{diagramId}/chartbook")
    public Response<Void> removeDiagram(@PathVariable String diagramId) {
        return CatalogControllerSupport.execute(() -> {
            chartbooks.removeDiagram(owner(), diagramId);
            return null;
        });
    }

    private CatalogOwner owner() {
        return CatalogControllerSupport.requiredOwner(ownerResolver);
    }

    private ChartbookResponseDTO view(ChartbookView source) {
        return new ChartbookResponseDTO(source.chartbookId(), source.name(), source.status().name(),
                source.diagramIds(), source.materialIds(), source.createdAt(), source.updatedAt());
    }
}
