package org.zipp.ai.trigger.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.dto.AutoMemoryResponseDTO;
import org.zipp.ai.api.dto.MemoryTextUpdateRequestDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.application.memory.AutoMemory;
import org.zipp.ai.application.memory.AutoMemoryFence;
import org.zipp.ai.application.memory.AutoMemoryManagementOutcome;
import org.zipp.ai.application.memory.AutoMemoryManagementService;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.util.List;
import java.util.function.Supplier;

/** Owner-fenced management for both user-global and Chartbook automatic Memory. */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name = "app.memory.auto-enabled", havingValue = "true")
public final class MemoryController {
    private final CurrentOwnerHttpResolver ownerResolver;
    private final AutoMemoryManagementService memories;

    public MemoryController(
            CurrentOwnerHttpResolver ownerResolver,
            AutoMemoryManagementService memories
    ) {
        this.ownerResolver = ownerResolver;
        this.memories = memories;
    }

    @GetMapping({"/memory", "/chartbooks/{chartbookId}/memory"})
    public Response<List<AutoMemoryResponseDTO>> list(
            @PathVariable(required = false) String chartbookId,
            @RequestParam(defaultValue = "true") boolean includeObserved,
            @RequestParam(defaultValue = "true") boolean includeDisabled
    ) {
        return execute(() -> memories.list(
                        scope(owner(), chartbookId), includeObserved, includeDisabled)
                .stream().map(this::view).toList());
    }

    @PatchMapping({"/memory/{memoryId}", "/chartbooks/{chartbookId}/memory/{memoryId}"})
    public Response<AutoMemoryResponseDTO> edit(
            @PathVariable(required = false) String chartbookId,
            @PathVariable String memoryId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody MemoryTextUpdateRequestDTO body
    ) {
        CatalogOwner owner = owner();
        return manage(() -> memories.edit(
                fence(owner, chartbookId, memoryId, ifMatch),
                body == null ? null : body.canonicalText()));
    }

    @PostMapping({
            "/memory/{memoryId}/disable",
            "/chartbooks/{chartbookId}/memory/{memoryId}/disable"
    })
    public Response<AutoMemoryResponseDTO> disable(
            @PathVariable(required = false) String chartbookId,
            @PathVariable String memoryId,
            @RequestHeader("If-Match") String ifMatch
    ) {
        CatalogOwner owner = owner();
        return manage(() -> memories.disable(fence(owner, chartbookId, memoryId, ifMatch)));
    }

    @PostMapping({
            "/memory/{memoryId}/activate",
            "/chartbooks/{chartbookId}/memory/{memoryId}/activate"
    })
    public Response<AutoMemoryResponseDTO> activate(
            @PathVariable(required = false) String chartbookId,
            @PathVariable String memoryId,
            @RequestHeader("If-Match") String ifMatch
    ) {
        CatalogOwner owner = owner();
        return manage(() -> memories.activate(fence(owner, chartbookId, memoryId, ifMatch)));
    }

    @DeleteMapping({"/memory/{memoryId}", "/chartbooks/{chartbookId}/memory/{memoryId}"})
    public Response<Void> delete(
            @PathVariable(required = false) String chartbookId,
            @PathVariable String memoryId,
            @RequestHeader("If-Match") String ifMatch
    ) {
        CatalogOwner owner = owner();
        return manageDelete(() -> memories.delete(
                fence(owner, chartbookId, memoryId, ifMatch)));
    }

    private CatalogOwner owner() {
        CatalogOwner owner = CatalogControllerSupport.requiredOwner(ownerResolver);
        owner.requireRegisteredUser();
        return owner;
    }

    private AutoMemoryScope scope(CatalogOwner owner, String chartbookId) {
        return chartbookId == null || chartbookId.isBlank()
                ? AutoMemoryScope.user(owner.ownerKey())
                : AutoMemoryScope.chartbook(owner.ownerKey(), chartbookId);
    }

    private AutoMemoryFence fence(
            CatalogOwner owner,
            String chartbookId,
            String memoryId,
            String ifMatch
    ) {
        return new AutoMemoryFence(
                scope(owner, chartbookId), memoryId, parseVersion(ifMatch));
    }

    private Response<AutoMemoryResponseDTO> manage(
            Supplier<AutoMemoryManagementOutcome> action
    ) {
        try {
            AutoMemoryManagementOutcome outcome = action.get();
            if (outcome instanceof AutoMemoryManagementOutcome.Updated updated) {
                return success(view(updated.memory()));
            }
            if (outcome instanceof AutoMemoryManagementOutcome.Gone gone) {
                return failure(gone.code());
            }
            if (outcome instanceof AutoMemoryManagementOutcome.Rejected rejected) {
                return failure(rejected.code());
            }
            return failure("AUTO_MEMORY_MANAGEMENT_INVALID");
        } catch (IllegalArgumentException exception) {
            return failure("AUTO_MEMORY_REQUEST_INVALID");
        } catch (RuntimeException exception) {
            return failure("TRANSIENT_DEPENDENCY");
        }
    }

    private Response<Void> manageDelete(Supplier<AutoMemoryManagementOutcome> action) {
        try {
            AutoMemoryManagementOutcome outcome = action.get();
            if (outcome instanceof AutoMemoryManagementOutcome.Deleted) {
                return success(null);
            }
            if (outcome instanceof AutoMemoryManagementOutcome.Gone gone) {
                return failure(gone.code());
            }
            if (outcome instanceof AutoMemoryManagementOutcome.Rejected rejected) {
                return failure(rejected.code());
            }
            return failure("AUTO_MEMORY_MANAGEMENT_INVALID");
        } catch (IllegalArgumentException exception) {
            return failure("AUTO_MEMORY_REQUEST_INVALID");
        } catch (RuntimeException exception) {
            return failure("TRANSIENT_DEPENDENCY");
        }
    }

    private <T> Response<T> execute(Supplier<T> action) {
        return CatalogControllerSupport.execute(action);
    }

    private AutoMemoryResponseDTO view(AutoMemory value) {
        return new AutoMemoryResponseDTO(
                value.memoryId(),
                value.scope().type().name(),
                value.scope().scopeKey(),
                value.type().name(),
                value.semanticKey(),
                value.title(),
                value.canonicalText(),
                value.status().name(),
                value.confidence(),
                value.evidenceCount(),
                value.explicit(),
                value.version(),
                value.updatedAt());
    }

    private static long parseVersion(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("If-Match is required");
        }
        String normalized = value.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.startsWith("memory-")) {
            normalized = normalized.substring("memory-".length());
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "If-Match must contain a memory version", exception);
        }
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code("0000").info("success").data(data).build();
    }

    private <T> Response<T> failure(String code) {
        return Response.<T>builder().code(code).info("memory request rejected").build();
    }
}
