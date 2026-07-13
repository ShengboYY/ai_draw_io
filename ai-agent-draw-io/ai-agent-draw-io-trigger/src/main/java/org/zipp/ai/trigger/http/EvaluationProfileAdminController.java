package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvaluationProfileVersion;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalControlPlaneAuditTypes;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvaluationProfileResolver;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;
import org.zipp.ai.types.enums.ResponseCode;

import java.util.List;

/** Read-only admin endpoint for source-controlled Evaluation Profile versions. */
@RestController
@RequestMapping("/api/v1/admin/evaluation-profiles")
public class EvaluationProfileAdminController {
    private final EvaluationProfileResolver profiles;
    private final AdminAuthorizationService authorization;
    private final AdminAuditLogService audits;

    public EvaluationProfileAdminController(EvaluationProfileResolver profiles,
                                            AdminAuthorizationService authorization,
                                            AdminAuditLogService audits) {
        this.profiles = profiles; this.authorization = authorization; this.audits = audits;
    }

    @GetMapping
    public Response<List<EvaluationProfileVersion>> list(HttpServletRequest request) {
        return authorization.currentAdmin(request).map(admin -> {
            audits.record(admin.getId(), "LIST_EVALUATION_PROFILES", EvalControlPlaneAuditTypes.EVALUATION_PROFILE,
                    null, "SUCCESS", request.getRemoteAddr(), request.getHeader("User-Agent"));
            return Response.<List<EvaluationProfileVersion>>builder().code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo()).data(profiles.list()).build();
        }).orElseGet(() -> Response.<List<EvaluationProfileVersion>>builder()
                .code(ResponseCode.AUTH_FORBIDDEN.getCode()).info(ResponseCode.AUTH_FORBIDDEN.getInfo()).build());
    }
}
