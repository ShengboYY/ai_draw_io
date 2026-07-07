package org.zipp.ai.trigger.http;

import lombok.extern.slf4j.Slf4j;
import org.zipp.ai.api.dto.CreateModelCredentialRequestDTO;
import org.zipp.ai.api.dto.ModelCredentialResponseDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.valobj.CreateModelCredentialCommand;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialSummary;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.account.service.IModelCredentialService;
import org.zipp.ai.domain.agent.service.chat.ProviderCatalog;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;
import java.util.Optional;

/** HTTP API for verified users to manage encrypted model credentials. */
@Slf4j
@RestController
@RequestMapping("/api/v1/model-credentials")
public class ModelCredentialController {

    private static final String SUCCESS = "0000";
    private static final String FAILURE = "0001";

    @Resource
    private IModelCredentialService modelCredentialService;

    @Resource
    private CurrentOwnerHttpResolver currentOwnerHttpResolver;

    @PostMapping
    public Response<ModelCredentialResponseDTO> create(@RequestBody CreateModelCredentialRequestDTO request) {
        Optional<String> userId = currentVerifiedUserId();
        if (userId.isEmpty()) {
            return verifiedLoginRequired();
        }
        try {
            ModelCredentialSummary created = modelCredentialService.create(CreateModelCredentialCommand.builder()
                    .userId(userId.get())
                    .provider(request == null ? null : request.getProvider())
                    .baseUrl(request == null ? null : request.getBaseUrl())
                    .model(request == null ? null : request.getModel())
                    .completionPath(request == null ? null : request.getCompletionPath())
                    .displayName(request == null ? null : request.getDisplayName())
                    .apiKey(request == null ? null : request.getApiKey())
                    .build());
            return Response.<ModelCredentialResponseDTO>builder()
                    .code(SUCCESS).info("成功").data(toDTO(created)).build();
        } catch (IllegalArgumentException e) {
            return Response.<ModelCredentialResponseDTO>builder().code(FAILURE).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("create model credential failed", e);
            return Response.<ModelCredentialResponseDTO>builder().code(FAILURE).info("create model credential failed").build();
        }
    }

    @GetMapping
    public Response<List<ModelCredentialResponseDTO>> list() {
        Optional<String> userId = currentVerifiedUserId();
        if (userId.isEmpty()) {
            return Response.<List<ModelCredentialResponseDTO>>builder()
                    .code(FAILURE).info("verified login required").build();
        }
        try {
            List<ModelCredentialResponseDTO> credentials = modelCredentialService.list(userId.get()).stream()
                    .map(this::toDTO)
                    .toList();
            return Response.<List<ModelCredentialResponseDTO>>builder()
                    .code(SUCCESS).info("成功").data(credentials).build();
        } catch (Exception e) {
            log.error("list model credentials failed", e);
            return Response.<List<ModelCredentialResponseDTO>>builder().code(FAILURE).info("list model credentials failed").build();
        }
    }

    @PostMapping("/{credentialId}/disable")
    public Response<Void> disable(@PathVariable("credentialId") String credentialId) {
        Optional<String> userId = currentVerifiedUserId();
        if (userId.isEmpty()) {
            return Response.<Void>builder().code(FAILURE).info("verified login required").build();
        }
        try {
            return mutationResult(modelCredentialService.disable(userId.get(), credentialId), "credential not found");
        } catch (IllegalArgumentException e) {
            return Response.<Void>builder().code(FAILURE).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("disable model credential failed", e);
            return Response.<Void>builder().code(FAILURE).info("disable model credential failed").build();
        }
    }

    @DeleteMapping("/{credentialId}")
    public Response<Void> delete(@PathVariable("credentialId") String credentialId) {
        Optional<String> userId = currentVerifiedUserId();
        if (userId.isEmpty()) {
            return Response.<Void>builder().code(FAILURE).info("verified login required").build();
        }
        try {
            return mutationResult(modelCredentialService.delete(userId.get(), credentialId), "credential not found");
        } catch (IllegalArgumentException e) {
            return Response.<Void>builder().code(FAILURE).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("delete model credential failed", e);
            return Response.<Void>builder().code(FAILURE).info("delete model credential failed").build();
        }
    }

    /** Provider presets for the frontend dropdown (endpoint/model hints). Not user-specific. */
    @GetMapping("/providers")
    public Response<List<ProviderPresetDTO>> providers() {
        List<ProviderPresetDTO> presets = ProviderCatalog.presets().stream()
                .map(p -> new ProviderPresetDTO(
                        p.id(), p.displayName(), p.baseUrl(), p.completionsPath(), p.models()))
                .toList();
        return Response.<List<ProviderPresetDTO>>builder().code(SUCCESS).info("成功").data(presets).build();
    }

    /** Frontend-facing provider preset. Structured-output tier stays server-side. */
    public record ProviderPresetDTO(String id,
                                    String displayName,
                                    String baseUrl,
                                    String completionsPath,
                                    List<String> models) {
    }

    private Optional<String> currentVerifiedUserId() {
        CurrentOwnerHttpResolver resolver = currentOwnerHttpResolver == null
                ? new CurrentOwnerHttpResolver() : currentOwnerHttpResolver;
        return resolver.resolve(null)
                .filter(ResolvedOwner::isAuthenticated)
                .map(ResolvedOwner::getOwnerId);
    }

    private Response<ModelCredentialResponseDTO> verifiedLoginRequired() {
        return Response.<ModelCredentialResponseDTO>builder()
                .code(FAILURE).info("verified login required").build();
    }

    private Response<Void> mutationResult(boolean changed, String failureInfo) {
        return changed
                ? Response.<Void>builder().code(SUCCESS).info("成功").build()
                : Response.<Void>builder().code(FAILURE).info(failureInfo).build();
    }

    private ModelCredentialResponseDTO toDTO(ModelCredentialSummary summary) {
        ModelCredentialResponseDTO dto = new ModelCredentialResponseDTO();
        dto.setId(summary.getId());
        dto.setProvider(summary.getProvider());
        dto.setBaseUrl(summary.getBaseUrl());
        dto.setModel(summary.getModel());
        dto.setCompletionPath(summary.getCompletionPath());
        dto.setDisplayName(summary.getDisplayName());
        dto.setMaskedApiKey(summary.getMaskedApiKey());
        dto.setEncryptionProvider(summary.getEncryptionProvider());
        dto.setEncryptionKeyId(summary.getEncryptionKeyId());
        dto.setKeyLastFour(summary.getKeyLastFour());
        dto.setStatus(summary.getStatus() == null ? null : summary.getStatus().name());
        dto.setCreatedAt(summary.getCreatedAt());
        dto.setUpdatedAt(summary.getUpdatedAt());
        dto.setDisabledAt(summary.getDisabledAt());
        return dto;
    }
}
