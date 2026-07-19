package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.account.model.entity.AnonymousWorkspace;
import org.zipp.ai.domain.account.model.valobj.AnonymousWorkspaceStatus;
import org.zipp.ai.domain.account.service.IAnonymousWorkspaceStore;
import org.zipp.ai.infrastructure.dao.IAnonymousWorkspaceMapper;
import org.zipp.ai.infrastructure.dao.po.AnonymousWorkspacePO;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/** MyBatis adapter for the anonymous workspace aggregate. */
@Repository
public class AnonymousWorkspaceRepository implements IAnonymousWorkspaceStore {

    @Resource
    private IAnonymousWorkspaceMapper anonymousWorkspaceMapper;

    public AnonymousWorkspaceRepository() {
    }

    /** Constructor injection keeps the persistence mapping testable without a Spring context. */
    AnonymousWorkspaceRepository(IAnonymousWorkspaceMapper anonymousWorkspaceMapper) {
        this.anonymousWorkspaceMapper = anonymousWorkspaceMapper;
    }

    @Override
    public void insert(AnonymousWorkspace workspace) {
        anonymousWorkspaceMapper.insert(toPo(workspace));
    }

    @Override
    public Optional<AnonymousWorkspace> findByCredentialId(String credentialId) {
        return Optional.ofNullable(anonymousWorkspaceMapper.selectByCredentialId(credentialId))
                .map(this::toDomain);
    }

    @Override
    public boolean saveClaim(AnonymousWorkspace workspace) {
        if (workspace.getStatus() != AnonymousWorkspaceStatus.CLAIMED) {
            throw new IllegalArgumentException("Only a claimed anonymous workspace can persist a claim");
        }
        return anonymousWorkspaceMapper.markClaimed(
                workspace.getCredentialId(),
                workspace.getClaimedByUserId(),
                toDate(workspace.getClaimedAt())) == 1;
    }

    private AnonymousWorkspacePO toPo(AnonymousWorkspace workspace) {
        AnonymousWorkspacePO po = new AnonymousWorkspacePO();
        po.setOwnerId(workspace.getOwnerId());
        po.setCredentialId(workspace.getCredentialId());
        po.setCredentialHash(workspace.getCredentialHash());
        po.setStatus(workspace.getStatus().name());
        po.setClaimedByUserId(workspace.getClaimedByUserId());
        po.setCreatedAt(toDate(workspace.getCreatedAt()));
        po.setClaimedAt(toDate(workspace.getClaimedAt()));
        return po;
    }

    private AnonymousWorkspace toDomain(AnonymousWorkspacePO po) {
        return AnonymousWorkspace.restore(
                po.getOwnerId(),
                po.getCredentialId(),
                po.getCredentialHash(),
                AnonymousWorkspaceStatus.valueOf(po.getStatus()),
                toInstant(po.getCreatedAt()),
                po.getClaimedByUserId(),
                toInstant(po.getClaimedAt()));
    }

    private Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
