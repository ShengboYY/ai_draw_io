package org.zipp.ai.trigger.http.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.account.service.IAnonymousWorkspaceIdentityService;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;

import java.util.List;

/** Application service coordinating content migration with the aggregate's credential revocation. */
@Service
public class AnonymousWorkspaceClaimService {

    private final IAnonymousWorkspaceIdentityService identityService;
    private final ICanvasStateStore canvasStateStore;

    public AnonymousWorkspaceClaimService(IAnonymousWorkspaceIdentityService identityService,
                                          ICanvasStateStore canvasStateStore) {
        this.identityService = identityService;
        this.canvasStateStore = canvasStateStore;
    }

    @Transactional
    public List<CanvasState> claim(String rawCredential, String targetUserId) {
        ResolvedOwner source = identityService.authenticate(rawCredential)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Anonymous workspace credential is invalid"));
        List<CanvasState> imported = canvasStateStore.importAnonymousWorkspace(
                source.getOwnerId(), targetUserId);
        // Persist the aggregate transition last; any failure rolls the content migration back too.
        identityService.claim(rawCredential, targetUserId);
        return imported;
    }
}
