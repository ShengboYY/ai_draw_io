package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.entity.AnonymousWorkspace;
import org.zipp.ai.domain.account.model.valobj.AnonymousWorkspaceStatus;
import org.zipp.ai.infrastructure.dao.IAnonymousWorkspaceMapper;
import org.zipp.ai.infrastructure.dao.po.AnonymousWorkspacePO;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnonymousWorkspaceRepositoryTest {

    @Test
    void shouldPersistAndRestoreTheAggregateWithoutRawSecret() {
        InMemoryMapper mapper = new InMemoryMapper();
        AnonymousWorkspaceRepository repository = new AnonymousWorkspaceRepository(mapper);
        AnonymousWorkspace workspace = AnonymousWorkspace.issue(
                "anon_123e4567-e89b-42d3-a456-426614174000",
                "awc_123e4567-e89b-42d3-a456-426614174000",
                "credential-hash-only",
                Instant.parse("2026-07-19T00:00:00Z"));

        repository.insert(workspace);

        AnonymousWorkspace restored = repository.findByCredentialId(workspace.getCredentialId()).orElseThrow();
        assertEquals(workspace.getOwnerId(), restored.getOwnerId());
        assertEquals("credential-hash-only", restored.getCredentialHash());
        assertEquals(AnonymousWorkspaceStatus.ACTIVE, restored.getStatus());
    }

    @Test
    void shouldPersistClaimOnlyWhileStoredWorkspaceIsActive() {
        InMemoryMapper mapper = new InMemoryMapper();
        AnonymousWorkspaceRepository repository = new AnonymousWorkspaceRepository(mapper);
        AnonymousWorkspace workspace = AnonymousWorkspace.issue(
                "anon_123e4567-e89b-42d3-a456-426614174000",
                "awc_123e4567-e89b-42d3-a456-426614174000",
                "credential-hash-only",
                Instant.parse("2026-07-19T00:00:00Z"));
        repository.insert(workspace);
        workspace.claimBy("usr_alice", Instant.parse("2026-07-19T01:00:00Z"));

        assertTrue(repository.saveClaim(workspace));
        AnonymousWorkspace claimed = repository.findByCredentialId(workspace.getCredentialId()).orElseThrow();
        assertEquals(AnonymousWorkspaceStatus.CLAIMED, claimed.getStatus());
        assertEquals("usr_alice", claimed.getClaimedByUserId());
    }

    private static final class InMemoryMapper implements IAnonymousWorkspaceMapper {

        private final Map<String, AnonymousWorkspacePO> records = new HashMap<>();

        @Override
        public int insert(AnonymousWorkspacePO workspace) {
            records.put(workspace.getCredentialId(), copy(workspace));
            return 1;
        }

        @Override
        public AnonymousWorkspacePO selectByCredentialId(String credentialId) {
            return copy(records.get(credentialId));
        }

        @Override
        public int markClaimed(String credentialId, String claimedByUserId, java.util.Date claimedAt) {
            AnonymousWorkspacePO current = records.get(credentialId);
            if (current == null || !AnonymousWorkspaceStatus.ACTIVE.name().equals(current.getStatus())) {
                return 0;
            }
            current.setStatus(AnonymousWorkspaceStatus.CLAIMED.name());
            current.setClaimedByUserId(claimedByUserId);
            current.setClaimedAt(claimedAt);
            return 1;
        }

        private AnonymousWorkspacePO copy(AnonymousWorkspacePO source) {
            if (source == null) {
                return null;
            }
            AnonymousWorkspacePO copy = new AnonymousWorkspacePO();
            copy.setOwnerId(source.getOwnerId());
            copy.setCredentialId(source.getCredentialId());
            copy.setCredentialHash(source.getCredentialHash());
            copy.setStatus(source.getStatus());
            copy.setClaimedByUserId(source.getClaimedByUserId());
            copy.setCreatedAt(source.getCreatedAt());
            copy.setClaimedAt(source.getClaimedAt());
            return copy;
        }
    }
}
