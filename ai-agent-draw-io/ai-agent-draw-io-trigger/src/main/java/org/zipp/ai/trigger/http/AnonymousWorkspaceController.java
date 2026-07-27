package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.dto.AnonymousWorkspaceResponseDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.valobj.IssuedAnonymousWorkspace;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.account.service.IAnonymousWorkspaceIdentityService;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/anonymous-workspaces")
public class AnonymousWorkspaceController {

    private final IAnonymousWorkspaceIdentityService identityService;
    private final AnonymousWorkspaceCookie workspaceCookie;

    public AnonymousWorkspaceController(IAnonymousWorkspaceIdentityService identityService,
                                        AnonymousWorkspaceCookie workspaceCookie) {
        this.identityService = identityService;
        this.workspaceCookie = workspaceCookie;
    }

    /** Ensures one valid anonymous capability without returning its secret in the response body. */
    @PostMapping
    public Response<AnonymousWorkspaceResponseDTO> ensure(HttpServletRequest request,
                                                          HttpServletResponse response) {
        Optional<ResolvedOwner> existing = workspaceCookie.read(request)
                .flatMap(identityService::authenticate);
        if (existing.isPresent()) {
            return success(existing.get().getOwnerId());
        }

        IssuedAnonymousWorkspace issued = identityService.issue();
        workspaceCookie.write(response, issued.getRawCredential());
        return success(issued.getOwnerId());
    }

    private Response<AnonymousWorkspaceResponseDTO> success(String ownerId) {
        return Response.<AnonymousWorkspaceResponseDTO>builder()
                .code("0000")
                .info("成功")
                .data(new AnonymousWorkspaceResponseDTO(ownerId))
                .build();
    }
}
