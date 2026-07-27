package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.retrieval.RequestSourceResolutionCommand;
import org.zipp.ai.domain.retrieval.port.RequestSourceResolutionPort;
import org.zipp.ai.domain.retrieval.port.SourceResolutionCandidate;
import org.zipp.ai.infrastructure.dao.retrieval.IOnlineRetrievalMapper;
import org.zipp.ai.infrastructure.dao.retrieval.po.OnlineSourcePO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Resolves client declarations through owner/scope-fenced MySQL projections. */
@Repository
public class MySqlRequestSourceResolutionAdapter implements RequestSourceResolutionPort {
    private final IOnlineRetrievalMapper mapper;
    private final MySqlConversationScopeKeyResolver conversationScopes;

    public MySqlRequestSourceResolutionAdapter(IOnlineRetrievalMapper mapper) {
        this(mapper, null);
    }

    @Autowired
    public MySqlRequestSourceResolutionAdapter(IOnlineRetrievalMapper mapper,
                                               MySqlConversationScopeKeyResolver conversationScopes) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.conversationScopes = conversationScopes;
    }

    @Override
    public List<SourceResolutionCandidate> resolveAttachments(RequestSourceResolutionCommand command) {
        return selectAttachments(command).stream().map(this::candidate).toList();
    }

    @Override
    public List<SourceResolutionCandidate> resolveExplicitVersions(RequestSourceResolutionCommand command) {
        return selectExplicit(command).stream().map(this::candidate).toList();
    }

    @Override
    public List<SourceResolutionCandidate> resolveAutomatic(RequestSourceResolutionCommand command, int limit) {
        return selectAutomatic(command, limit).stream().map(this::candidate).toList();
    }

    @Override
    public int countPendingConversationUploads(RequestSourceResolutionCommand command) {
        return conversationKeys(command).stream()
                .mapToInt(key -> value(mapper.countPendingConversationUploads(command.owner().ownerKey(), key)))
                .sum();
    }

    private List<OnlineSourcePO> selectAttachments(RequestSourceResolutionCommand command) {
        LinkedHashMap<String, OnlineSourcePO> merged = new LinkedHashMap<>();
        for (String key : conversationKeys(command)) {
            mapper.selectConversationAttachmentSources(command.owner().ownerType().name(),
                            command.owner().ownerKey(), key, command.attachmentUploadIds())
                    .forEach(row -> merged.putIfAbsent(row.getVersionId(), row));
        }
        return List.copyOf(merged.values());
    }

    private List<OnlineSourcePO> selectExplicit(RequestSourceResolutionCommand command) {
        LinkedHashMap<String, OnlineSourcePO> merged = new LinkedHashMap<>();
        for (String key : conversationKeys(command)) {
            mapper.selectExplicitSources(command.owner().ownerType().name(), command.owner().ownerKey(),
                            command.diagramId(), key, command.selectedVersionIds())
                    .forEach(row -> merged.putIfAbsent(row.getVersionId(), row));
        }
        return List.copyOf(merged.values());
    }

    private List<OnlineSourcePO> selectAutomatic(RequestSourceResolutionCommand command, int limit) {
        LinkedHashMap<String, OnlineSourcePO> merged = new LinkedHashMap<>();
        for (String key : conversationKeys(command)) {
            mapper.selectAutomaticSources(command.owner().ownerType().name(), command.owner().ownerKey(),
                            command.diagramId(), key, limit)
                    .forEach(row -> merged.putIfAbsent(row.getVersionId(), row));
        }
        return merged.values().stream().limit(limit).toList();
    }

    private List<String> conversationKeys(RequestSourceResolutionCommand command) {
        if (conversationScopes == null) return List.of(command.conversationId());
        return conversationScopes.readableScopeKeys(
                new AuthenticatedActor(command.owner().ownerKey(), command.owner().ownerKey()),
                command.conversationId(), command.diagramId()).allKeys();
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private SourceResolutionCandidate candidate(OnlineSourcePO row) {
        return new SourceResolutionCandidate(
                text(row.getDeclarationId()).isBlank() ? row.getVersionId() : row.getDeclarationId(),
                row.getMaterialId(), row.getVersionId(), row.getRevisionId(), text(row.getKind()),
                text(row.getDisplayName()),
                MaterialScopeType.valueOf(row.getScopeType()), text(row.getScopeKey()), text(row.getState()),
                text(row.getUploadState()), row.isConversationScoped(), row.isHasText(),
                row.isHasVisual(), row.isPinned());
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
