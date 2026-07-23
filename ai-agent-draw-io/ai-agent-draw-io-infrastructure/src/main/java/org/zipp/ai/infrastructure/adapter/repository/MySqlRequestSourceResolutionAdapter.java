package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.retrieval.RequestSourceResolutionCommand;
import org.zipp.ai.domain.retrieval.port.RequestSourceResolutionPort;
import org.zipp.ai.domain.retrieval.port.SourceResolutionCandidate;
import org.zipp.ai.infrastructure.dao.retrieval.IOnlineRetrievalMapper;
import org.zipp.ai.infrastructure.dao.retrieval.po.OnlineSourcePO;

import java.util.List;
import java.util.Objects;

/** Resolves client declarations through owner/scope-fenced MySQL projections. */
@Repository
public class MySqlRequestSourceResolutionAdapter implements RequestSourceResolutionPort {
    private final IOnlineRetrievalMapper mapper;

    public MySqlRequestSourceResolutionAdapter(IOnlineRetrievalMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public List<SourceResolutionCandidate> resolveAttachments(RequestSourceResolutionCommand command) {
        return mapper.selectConversationAttachmentSources(command.owner().ownerType().name(),
                        command.owner().ownerKey(), command.conversationId(), command.attachmentUploadIds())
                .stream().map(this::candidate).toList();
    }

    @Override
    public List<SourceResolutionCandidate> resolveExplicitVersions(RequestSourceResolutionCommand command) {
        return mapper.selectExplicitSources(command.owner().ownerType().name(), command.owner().ownerKey(),
                        command.diagramId(), command.conversationId(), command.selectedVersionIds())
                .stream().map(this::candidate).toList();
    }

    @Override
    public List<SourceResolutionCandidate> resolveAutomatic(RequestSourceResolutionCommand command, int limit) {
        return mapper.selectAutomaticSources(command.owner().ownerType().name(), command.owner().ownerKey(),
                        command.diagramId(), command.conversationId(), limit)
                .stream().map(this::candidate).toList();
    }

    private SourceResolutionCandidate candidate(OnlineSourcePO row) {
        return new SourceResolutionCandidate(
                text(row.getDeclarationId()).isBlank() ? row.getVersionId() : row.getDeclarationId(),
                row.getMaterialId(), row.getVersionId(), row.getRevisionId(), text(row.getKind()),
                MaterialScopeType.valueOf(row.getScopeType()), text(row.getScopeKey()), text(row.getState()),
                text(row.getUploadState()), row.isConversationScoped(), row.isHasText(),
                row.isHasVisual(), row.isPinned());
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
