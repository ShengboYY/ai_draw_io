package org.zipp.ai.trigger.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.dto.ChartbookProfileRequestDTO;
import org.zipp.ai.api.dto.ChartbookProfileResponseDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookProfile;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookProfilePatch;
import org.zipp.ai.domain.chartbook.service.ChartbookProfileService;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

/** HTTP mapping only; ownership, archive state and CAS semantics remain in the Profile module. */
@RestController
@RequestMapping("/api/v1/chartbooks/{chartbookId}/profile")
@ConditionalOnProperty(name = "app.material-catalog.enabled", havingValue = "true")
public class ChartbookProfileController {
    private final CurrentOwnerHttpResolver ownerResolver;
    private final ChartbookProfileService profiles;

    public ChartbookProfileController(CurrentOwnerHttpResolver ownerResolver, ChartbookProfileService profiles) {
        this.ownerResolver = ownerResolver;
        this.profiles = profiles;
    }

    @GetMapping
    public Response<ChartbookProfileResponseDTO> get(@PathVariable String chartbookId) {
        return CatalogControllerSupport.execute(() -> view(profiles.find(owner(), chartbookId)));
    }

    @PatchMapping
    public Response<ChartbookProfileResponseDTO> patch(
            @PathVariable String chartbookId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ChartbookProfileRequestDTO body) {
        return CatalogControllerSupport.execute(() -> view(profiles.update(
                owner(), chartbookId, patch(body), parseVersion(ifMatch), idempotencyKey)));
    }

    private ChartbookProfilePatch patch(ChartbookProfileRequestDTO body) {
        if (body == null) throw new IllegalArgumentException("profile request is required");
        return new ChartbookProfilePatch(body.instructions(), body.goal(), body.summary(), body.glossary(),
                body.defaultStyle() == null ? null
                        : new org.zipp.ai.domain.chartbook.model.valobj.DiagramStyleDefaults(body.defaultStyle()),
                body.stableConstraints());
    }

    private CatalogOwner owner() {
        return CatalogControllerSupport.requiredOwner(ownerResolver);
    }

    private static long parseVersion(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("If-Match is required");
        String normalized = value.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.startsWith("profile-")) normalized = normalized.substring("profile-".length());
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must contain a profile version", exception);
        }
    }

    private ChartbookProfileResponseDTO view(ChartbookProfile profile) {
        return new ChartbookProfileResponseDTO(profile.chartbookId(), profile.version(), profile.instructions(),
                profile.goal(), profile.summary(), profile.glossary(), profile.defaultStyle().values(),
                profile.stableConstraints(), profile.profileState().name(), profile.updatedAt());
    }
}
