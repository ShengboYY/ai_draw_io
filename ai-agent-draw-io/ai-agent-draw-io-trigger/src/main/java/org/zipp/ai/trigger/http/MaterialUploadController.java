package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.dto.InitiateMaterialUploadRequestDTO;
import org.zipp.ai.api.dto.MaterialUploadResponseDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.ingestion.exception.UploadAdmissionException;
import org.zipp.ai.domain.ingestion.model.valobj.CompleteUploadCommand;
import org.zipp.ai.domain.ingestion.model.valobj.InitiateUploadCommand;
import org.zipp.ai.domain.ingestion.model.valobj.InitiateUploadResult;
import org.zipp.ai.domain.ingestion.model.valobj.UploadSessionStatus;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;
import org.zipp.ai.domain.ingestion.service.IMaterialUploadService;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;
import org.zipp.ai.trigger.http.service.DailyHmacRateKeyFactory;

import java.util.Locale;

@RestController
@RequestMapping("/api/v1/material-uploads")
@ConditionalOnProperty(name = "app.material-upload.enabled", havingValue = "true")
public class MaterialUploadController {
    private static final Logger LOG = LoggerFactory.getLogger(MaterialUploadController.class);

    private final CurrentOwnerHttpResolver ownerResolver;
    private final IMaterialUploadService uploadService;
    private final DailyHmacRateKeyFactory rateKeyFactory;

    public MaterialUploadController(CurrentOwnerHttpResolver ownerResolver,
                                    IMaterialUploadService uploadService,
                                    DailyHmacRateKeyFactory rateKeyFactory) {
        this.ownerResolver = ownerResolver;
        this.uploadService = uploadService;
        this.rateKeyFactory = rateKeyFactory;
    }

    @PostMapping
    public Response<MaterialUploadResponseDTO> initiate(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InitiateMaterialUploadRequestDTO body,
            HttpServletRequest request) {
        try {
            ResolvedOwner owner = requiredOwner();
            var target = body.target();
            InitiateUploadResult result = uploadService.initiate(new InitiateUploadCommand(
                    owner.getOwnerType(), owner.getOwnerId(), idempotencyKey, body.displayName(), body.mediaType(),
                    body.byteSize(), body.sha256(), new UploadTarget(
                    enumValue(MaterialScopeType.class, target.scopeType()), target.scopeId(),
                    enumValue(RetentionClass.class, target.retentionClass())),
                    target.diagramId(), body.newVersionOfMaterialId(), rateKeyFactory.create(request.getRemoteAddr()),
                    body.batchFileCount() == null ? 1 : body.batchFileCount()));
            return success(from(result));
        } catch (UploadAdmissionException e) {
            LOG.warn("Material upload initiation rejected: {}", e.code());
            return failure(e.code().name());
        } catch (IllegalArgumentException e) {
            LOG.warn("Material upload initiation request was invalid");
            return failure("UPLOAD_REQUEST_INVALID");
        } catch (RuntimeException e) {
            // Keep user-controlled upload details out of logs while retaining the dependency stack trace.
            LOG.error("Material upload initiation failed unexpectedly", e);
            return failure("TRANSIENT_DEPENDENCY");
        }
    }

    @PostMapping("/{uploadId}/complete")
    public Response<MaterialUploadResponseDTO> complete(@PathVariable String uploadId) {
        try {
            ResolvedOwner owner = requiredOwner();
            return success(from(uploadService.complete(
                    new CompleteUploadCommand(owner.getOwnerType(), owner.getOwnerId(), uploadId))));
        } catch (UploadAdmissionException e) {
            return failure(e.code().name());
        } catch (IllegalArgumentException e) {
            return failure("UPLOAD_REQUEST_INVALID");
        } catch (RuntimeException e) {
            return failure("TRANSIENT_DEPENDENCY");
        }
    }

    @GetMapping("/{uploadId}")
    public Response<MaterialUploadResponseDTO> status(@PathVariable String uploadId) {
        try {
            ResolvedOwner owner = requiredOwner();
            return success(from(uploadService.status(
                    new CompleteUploadCommand(owner.getOwnerType(), owner.getOwnerId(), uploadId))));
        } catch (UploadAdmissionException e) {
            return failure(e.code().name());
        } catch (IllegalArgumentException e) {
            return failure("UPLOAD_REQUEST_INVALID");
        } catch (RuntimeException e) {
            return failure("TRANSIENT_DEPENDENCY");
        }
    }

    private ResolvedOwner requiredOwner() {
        return ownerResolver.resolve(null).orElseThrow(() -> new IllegalArgumentException("owner credential required"));
    }

    private static MaterialUploadResponseDTO from(InitiateUploadResult result) {
        var policy = result.postPolicy();
        return new MaterialUploadResponseDTO(result.uploadId(), result.state().name(),
                policy == null ? null : new MaterialUploadResponseDTO.BrowserPostPolicyDTO(
                        policy.url(), policy.fields(), policy.expiresAt()), null, null, null, null);
    }

    private static MaterialUploadResponseDTO from(UploadSessionStatus status) {
        return new MaterialUploadResponseDTO(status.uploadId(), status.state().name(), null,
                status.pinnedObjectVersionId(), status.materialId(), status.versionId(), status.errorCode());
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null) {
            throw new IllegalArgumentException("enum value is required");
        }
        return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    }

    private static <T> Response<T> success(T data) {
        return Response.<T>builder().code("0000").info("success").data(data).build();
    }

    private static <T> Response<T> failure(String code) {
        return Response.<T>builder().code(code).info("upload request rejected").build();
    }
}
