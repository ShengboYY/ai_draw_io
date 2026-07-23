package org.zipp.ai.trigger.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.dto.MaterialCapabilitiesDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.operations.CapabilityState;
import org.zipp.ai.domain.operations.MaterialCapability;
import org.zipp.ai.domain.operations.MaterialCapabilityReport;
import org.zipp.ai.domain.operations.MaterialCapabilityService;
import org.zipp.ai.domain.operations.MaterialFeatureSet;
import org.zipp.ai.domain.operations.MaterialReleaseApproval;

import java.util.List;

/** Always-registered, public capability endpoint; it intentionally excludes operational internals. */
@RestController
@RequestMapping("/api/v1/material-capabilities")
public class MaterialCapabilitiesController {
    private static final List<String> ACCEPTED_MIME_TYPES = List.of(
            "application/pdf", "image/png", "image/jpeg");

    private final MaterialCapabilityService capabilities;
    private final MaterialFeatureSet featureSet;
    private final MaterialReleaseApproval releaseApproval;
    private final boolean previewEnabled;
    private final boolean denseRetrievalEnabled;
    private final boolean visualObservationEnabled;
    private final boolean directImageConversionEnabled;
    private final int maxBatchFiles;

    public MaterialCapabilitiesController(
            MaterialCapabilityService capabilities,
            MaterialFeatureSet featureSet,
            MaterialReleaseApproval releaseApproval,
            @Value("${app.material-preview.enabled:false}") boolean previewEnabled,
            @Value("${app.material-rag.dense-enabled:false}") boolean denseRetrievalEnabled,
            @Value("${app.material-visual-observation.enabled:false}") boolean visualObservationEnabled,
            @Value("${app.material-direct-image-conversion.enabled:false}") boolean directImageConversionEnabled,
            @Value("${app.material-upload.max-batch-files:10}") int maxBatchFiles) {
        this.capabilities = capabilities;
        this.featureSet = featureSet;
        this.releaseApproval = releaseApproval;
        this.previewEnabled = previewEnabled;
        this.denseRetrievalEnabled = denseRetrievalEnabled;
        this.visualObservationEnabled = visualObservationEnabled;
        this.directImageConversionEnabled = directImageConversionEnabled;
        this.maxBatchFiles = Math.max(1, maxBatchFiles);
    }

    @GetMapping
    public Response<MaterialCapabilitiesDTO> capabilities() {
        MaterialCapabilityReport report = capabilities.assess(featureSet, releaseApproval.releasable());
        CapabilityState upload = report.capability(MaterialCapability.MATERIAL_UPLOAD);
        CapabilityState retrieval = report.capability(MaterialCapability.RETRIEVAL);
        CapabilityState visualObservation = dependent(visualObservationEnabled, upload);
        CapabilityState directImageConversion = dependent(directImageConversionEnabled, visualObservation);
        return Response.<MaterialCapabilitiesDTO>builder().code("0000").info("success")
                .data(new MaterialCapabilitiesDTO(upload.name(),
                        report.capability(MaterialCapability.LIBRARY).name(),
                        dependent(previewEnabled, report.capability(MaterialCapability.LIBRARY)).name(),
                        retrieval.name(), dependent(denseRetrievalEnabled, retrieval).name(),
                        visualObservation.name(), directImageConversion.name(),
                        report.capability(MaterialCapability.ANONYMOUS_UPLOAD).name(),
                        ACCEPTED_MIME_TYPES, maxBatchFiles))
                .build();
    }

    private CapabilityState dependent(boolean enabled, CapabilityState prerequisite) {
        if (!enabled) return CapabilityState.DISABLED;
        return prerequisite == CapabilityState.AVAILABLE ? CapabilityState.AVAILABLE : CapabilityState.MISCONFIGURED;
    }
}
