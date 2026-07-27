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
import org.zipp.ai.api.dto.MemoryCandidateConfirmationRequestDTO;
import org.zipp.ai.api.dto.MemoryCandidateResponseDTO;
import org.zipp.ai.api.dto.MemoryResponseDTO;
import org.zipp.ai.api.dto.MemoryTextUpdateRequestDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.application.memory.ConfirmedMemory;
import org.zipp.ai.application.memory.ConfirmedMemoryEditCommand;
import org.zipp.ai.application.memory.ConfirmedMemoryFence;
import org.zipp.ai.application.memory.MemoryCandidateFence;
import org.zipp.ai.application.memory.MemoryCandidateProposal;
import org.zipp.ai.application.memory.MemoryCandidateService;
import org.zipp.ai.application.memory.MemoryManagementOutcome;
import org.zipp.ai.application.memory.MemoryManagementPort;
import org.zipp.ai.application.memory.MemoryMaterializeOutcome;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.util.List;
import java.util.function.Supplier;

/** HTTP entry point for explicit Memory confirmation and owner-fenced management. */
@RestController
@RequestMapping("/api/v1/chartbooks/{chartbookId}/memory")
@ConditionalOnProperty(name = "app.material-catalog.enabled", havingValue = "true")
public final class MemoryController {
    private final CurrentOwnerHttpResolver ownerResolver;
    private final MemoryCandidateService candidates;
    private final MemoryManagementPort memories;

    public MemoryController(CurrentOwnerHttpResolver ownerResolver,
                            MemoryCandidateService candidates,
                            MemoryManagementPort memories) {
        this.ownerResolver = ownerResolver;
        this.candidates = candidates;
        this.memories = memories;
    }

    @GetMapping("/candidates")
    public Response<List<MemoryCandidateResponseDTO>> pendingCandidates(@PathVariable String chartbookId) {
        return execute(() -> candidates.listPending(owner().ownerKey(), chartbookId).stream()
                .map(this::candidateView).toList());
    }

    @PostMapping("/candidates/{candidateId}/confirm")
    public Response<MemoryResponseDTO> confirm(
            @PathVariable String chartbookId,
            @PathVariable String candidateId,
            @RequestBody MemoryCandidateConfirmationRequestDTO body) {
        return materialize(() -> candidates.confirm(candidateFence(owner(), chartbookId, candidateId, body)));
    }

    @PostMapping("/candidates/{candidateId}/revoke")
    public Response<Void> revoke(
            @PathVariable String chartbookId,
            @PathVariable String candidateId,
            @RequestBody MemoryCandidateConfirmationRequestDTO body) {
        return materializeVoid(() -> candidates.revoke(candidateFence(owner(), chartbookId, candidateId, body)));
    }

    @GetMapping
    public Response<List<MemoryResponseDTO>> list(
            @PathVariable String chartbookId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return execute(() -> memories.list(owner().ownerKey(), chartbookId, includeDisabled).stream()
                .map(this::memoryView).toList());
    }

    @PatchMapping("/{memoryId}")
    public Response<MemoryResponseDTO> edit(
            @PathVariable String chartbookId,
            @PathVariable String memoryId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody MemoryTextUpdateRequestDTO body) {
        return manage(() -> memories.edit(new ConfirmedMemoryEditCommand(
                memoryFence(owner(), chartbookId, memoryId, ifMatch), body == null ? null : body.canonicalText())));
    }

    @PostMapping("/{memoryId}/disable")
    public Response<MemoryResponseDTO> disable(
            @PathVariable String chartbookId,
            @PathVariable String memoryId,
            @RequestHeader("If-Match") String ifMatch) {
        return manage(() -> memories.disable(memoryFence(owner(), chartbookId, memoryId, ifMatch)));
    }

    @DeleteMapping("/{memoryId}")
    public Response<Void> delete(
            @PathVariable String chartbookId,
            @PathVariable String memoryId,
            @RequestHeader("If-Match") String ifMatch) {
        return manageVoid(() -> memories.delete(memoryFence(owner(), chartbookId, memoryId, ifMatch)));
    }

    private CatalogOwner owner() {
        CatalogOwner owner = CatalogControllerSupport.requiredOwner(ownerResolver);
        owner.requireRegisteredUser();
        return owner;
    }

    private MemoryCandidateFence candidateFence(
            CatalogOwner owner,
            String chartbookId,
            String candidateId,
            MemoryCandidateConfirmationRequestDTO body) {
        if (body == null) throw new IllegalArgumentException("confirmation request is required");
        return new MemoryCandidateFence(
                new TurnKey(owner.ownerKey(), body.sourceConversationId(), body.sourceTurnId()),
                chartbookId, candidateId, required(body.declarationDigest(), "declarationDigest"));
    }

    private ConfirmedMemoryFence memoryFence(
            CatalogOwner owner, String chartbookId, String memoryId, String ifMatch) {
        return new ConfirmedMemoryFence(owner.ownerKey(), chartbookId, memoryId, parseVersion(ifMatch));
    }

    private <T> Response<T> execute(Supplier<T> action) {
        return CatalogControllerSupport.execute(action);
    }

    private Response<MemoryResponseDTO> materialize(Supplier<MemoryMaterializeOutcome> action) {
        try {
            MemoryMaterializeOutcome outcome = action.get();
            if (outcome instanceof MemoryMaterializeOutcome.Materialized value) {
                return success(memoryView(value.memory()));
            }
            if (outcome instanceof MemoryMaterializeOutcome.AlreadyMaterialized value) {
                return success(memoryView(value.memory()));
            }
            if (outcome instanceof MemoryMaterializeOutcome.Gone value) return failure(value.code());
            return failure(((MemoryMaterializeOutcome.Rejected) outcome).code());
        } catch (IllegalArgumentException exception) {
            return failure("MEMORY_REQUEST_INVALID");
        } catch (RuntimeException exception) {
            return failure("TRANSIENT_DEPENDENCY");
        }
    }

    private Response<Void> materializeVoid(Supplier<MemoryMaterializeOutcome> action) {
        try {
            MemoryMaterializeOutcome outcome = action.get();
            if (outcome instanceof MemoryMaterializeOutcome.Gone value
                    && "MEMORY_CANDIDATE_REVOKED".equals(value.code())) return success(null);
            if (outcome instanceof MemoryMaterializeOutcome.Gone value) return failure(value.code());
            return failure(((MemoryMaterializeOutcome.Rejected) outcome).code());
        } catch (IllegalArgumentException exception) {
            return failure("MEMORY_REQUEST_INVALID");
        } catch (RuntimeException exception) {
            return failure("TRANSIENT_DEPENDENCY");
        }
    }

    private Response<MemoryResponseDTO> manage(Supplier<MemoryManagementOutcome> action) {
        try {
            MemoryManagementOutcome outcome = action.get();
            if (outcome instanceof MemoryManagementOutcome.Updated value) {
                return success(memoryView(value.memory()));
            }
            if (outcome instanceof MemoryManagementOutcome.Gone value) return failure(value.code());
            return failure(((MemoryManagementOutcome.Rejected) outcome).code());
        } catch (IllegalArgumentException exception) {
            return failure("MEMORY_REQUEST_INVALID");
        } catch (RuntimeException exception) {
            return failure("TRANSIENT_DEPENDENCY");
        }
    }

    private Response<Void> manageVoid(Supplier<MemoryManagementOutcome> action) {
        try {
            MemoryManagementOutcome outcome = action.get();
            if (outcome instanceof MemoryManagementOutcome.Gone value
                    && "MEMORY_DELETED".equals(value.code())) return success(null);
            if (outcome instanceof MemoryManagementOutcome.Gone value) return failure(value.code());
            return failure(((MemoryManagementOutcome.Rejected) outcome).code());
        } catch (IllegalArgumentException exception) {
            return failure("MEMORY_REQUEST_INVALID");
        } catch (RuntimeException exception) {
            return failure("TRANSIENT_DEPENDENCY");
        }
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code("0000").info("success").data(data).build();
    }

    private <T> Response<T> failure(String code) {
        return Response.<T>builder().code(code).info("memory request rejected").build();
    }

    private MemoryCandidateResponseDTO candidateView(MemoryCandidateProposal value) {
        return new MemoryCandidateResponseDTO(value.candidateId(), value.chartbookId(), value.diagramId(),
                value.turn().canonicalConversationId(), value.turn().turnId(), value.decisionKey(),
                value.applicabilityStage(), value.scope(), value.canonicalText(), value.policyVersion(),
                value.declarationDigest(), value.status().name(), value.version(), value.expiresAt());
    }

    private MemoryResponseDTO memoryView(ConfirmedMemory value) {
        return new MemoryResponseDTO(value.memoryId(), value.chartbookId(),
                value.sourceTurn().canonicalConversationId(), value.sourceTurn().turnId(), value.decisionKey(),
                value.applicabilityStage(), value.scope(), value.canonicalText(), value.status().name(),
                value.version());
    }

    private static long parseVersion(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("If-Match is required");
        String normalized = value.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.startsWith("memory-")) normalized = normalized.substring("memory-".length());
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must contain a memory version", exception);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
