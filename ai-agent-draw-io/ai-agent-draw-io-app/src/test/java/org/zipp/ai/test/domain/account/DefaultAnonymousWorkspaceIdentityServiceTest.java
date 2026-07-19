package org.zipp.ai.test.domain.account;

import org.junit.Test;
import org.zipp.ai.domain.account.model.entity.AnonymousWorkspace;
import org.zipp.ai.domain.account.model.valobj.AnonymousWorkspaceStatus;
import org.zipp.ai.domain.account.model.valobj.IssuedAnonymousWorkspace;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.account.service.DefaultAnonymousWorkspaceIdentityService;
import org.zipp.ai.domain.account.service.IAnonymousWorkspaceCredentialHasher;
import org.zipp.ai.domain.account.service.IAnonymousWorkspaceStore;
import org.zipp.ai.domain.account.service.ISecureTokenFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class DefaultAnonymousWorkspaceIdentityServiceTest {

    private static final String RAW_SECRET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG";

    private final InMemoryAnonymousWorkspaceStore store = new InMemoryAnonymousWorkspaceStore();
    private final DefaultAnonymousWorkspaceIdentityService service = new DefaultAnonymousWorkspaceIdentityService(
            store,
            () -> RAW_SECRET,
            new PrefixCredentialHasher());

    @Test
    public void shouldIssueServerOwnedWorkspaceWithoutPersistingRawCredential() {
        IssuedAnonymousWorkspace issued = service.issue();

        assertTrue(issued.getOwnerId().startsWith("anon_"));
        assertTrue(issued.getRawCredential().startsWith("awc_"));
        AnonymousWorkspace persisted = store.onlyWorkspace();
        assertEquals(issued.getOwnerId(), persisted.getOwnerId());
        assertEquals(AnonymousWorkspaceStatus.ACTIVE, persisted.getStatus());
        assertNotEquals(issued.getRawCredential(), persisted.getCredentialHash());
        assertFalse(persisted.getCredentialHash().contains(RAW_SECRET));
    }

    @Test
    public void shouldResolveOnlyTheWorkspaceProvenByTheServerIssuedSecret() {
        IssuedAnonymousWorkspace issued = service.issue();

        Optional<ResolvedOwner> owner = service.authenticate(issued.getRawCredential());
        Optional<ResolvedOwner> attacker = service.authenticate(
                issued.getRawCredential().replace(RAW_SECRET, "wrong-secret-with-enough-entropy-000000000000"));

        assertTrue(owner.isPresent());
        assertEquals(issued.getOwnerId(), owner.get().getOwnerId());
        assertFalse(attacker.isPresent());
        assertFalse(service.authenticate(issued.getOwnerId()).isPresent());
    }

    @Test
    public void shouldInvalidateAnonymousCredentialAfterWorkspaceIsClaimed() {
        IssuedAnonymousWorkspace issued = service.issue();

        String claimedOwnerId = service.claim(issued.getRawCredential(), "usr_alice");

        assertEquals(issued.getOwnerId(), claimedOwnerId);
        assertEquals(AnonymousWorkspaceStatus.CLAIMED, store.onlyWorkspace().getStatus());
        assertEquals("usr_alice", store.onlyWorkspace().getClaimedByUserId());
        assertFalse(service.authenticate(issued.getRawCredential()).isPresent());
    }

    private static final class PrefixCredentialHasher implements IAnonymousWorkspaceCredentialHasher {

        @Override
        public String hash(String rawSecret) {
            return "hashed:" + Integer.toHexString(rawSecret.hashCode());
        }

        @Override
        public boolean matches(String rawSecret, String expectedHash) {
            return hash(rawSecret).equals(expectedHash);
        }
    }

    private static final class InMemoryAnonymousWorkspaceStore implements IAnonymousWorkspaceStore {

        private final Map<String, AnonymousWorkspace> workspaces = new HashMap<>();

        @Override
        public void insert(AnonymousWorkspace workspace) {
            workspaces.put(workspace.getCredentialId(), workspace);
        }

        @Override
        public Optional<AnonymousWorkspace> findByCredentialId(String credentialId) {
            return Optional.ofNullable(workspaces.get(credentialId));
        }

        @Override
        public boolean saveClaim(AnonymousWorkspace workspace) {
            workspaces.put(workspace.getCredentialId(), workspace);
            return true;
        }

        private AnonymousWorkspace onlyWorkspace() {
            assertEquals(1, workspaces.size());
            return workspaces.values().iterator().next();
        }
    }
}
