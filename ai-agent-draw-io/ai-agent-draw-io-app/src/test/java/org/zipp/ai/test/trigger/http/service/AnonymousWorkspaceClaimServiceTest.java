package org.zipp.ai.test.trigger.http.service;

import org.junit.Test;
import org.zipp.ai.domain.account.model.valobj.IssuedAnonymousWorkspace;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.account.service.IAnonymousWorkspaceIdentityService;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.trigger.http.service.AnonymousWorkspaceClaimService;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class AnonymousWorkspaceClaimServiceTest {

    @Test
    public void shouldDeriveSourceOwnerFromCredentialAndThenRevokeIt() {
        StubIdentity identity = new StubIdentity();
        StubCanvasStore canvases = new StubCanvasStore();
        AnonymousWorkspaceClaimService service = new AnonymousWorkspaceClaimService(identity, canvases);

        List<CanvasState> imported = service.claim("valid-cookie-secret", "usr_alice");

        assertEquals("anon_source", canvases.sourceOwnerId);
        assertEquals("usr_alice", canvases.targetOwnerId);
        assertEquals("valid-cookie-secret", identity.claimedCredential);
        assertEquals(1, imported.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectGuessedOwnerIdBecauseItIsNotACredential() {
        AnonymousWorkspaceClaimService service = new AnonymousWorkspaceClaimService(
                new StubIdentity(), new StubCanvasStore());

        service.claim("anon_source", "usr_alice");
    }

    private static final class StubIdentity implements IAnonymousWorkspaceIdentityService {
        private String claimedCredential;

        @Override
        public IssuedAnonymousWorkspace issue() {
            throw new UnsupportedOperationException("Not needed by claim tests");
        }

        @Override
        public Optional<ResolvedOwner> authenticate(String rawCredential) {
            return "valid-cookie-secret".equals(rawCredential)
                    ? Optional.of(ResolvedOwner.anonymous("anon_source"))
                    : Optional.empty();
        }

        @Override
        public String claim(String rawCredential, String targetUserId) {
            claimedCredential = rawCredential;
            return "anon_source";
        }
    }

    private static final class StubCanvasStore implements ICanvasStateStore {
        private String sourceOwnerId;
        private String targetOwnerId;

        @Override
        public List<CanvasState> importAnonymousWorkspace(String anonymousOwnerId, String targetOwnerId) {
            this.sourceOwnerId = anonymousOwnerId;
            this.targetOwnerId = targetOwnerId;
            return List.of(CanvasState.builder().diagramId("diagram-1").build());
        }

        @Override
        public Optional<CanvasState> find(String userId, String diagramId) {
            return Optional.empty();
        }

        @Override
        public CanvasState save(CanvasState state) {
            return state;
        }
    }
}
