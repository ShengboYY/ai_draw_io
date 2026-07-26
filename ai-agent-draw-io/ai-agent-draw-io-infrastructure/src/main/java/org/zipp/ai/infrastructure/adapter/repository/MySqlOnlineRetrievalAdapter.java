package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.retrieval.*;
import org.zipp.ai.domain.retrieval.port.*;
import org.zipp.ai.infrastructure.dao.retrieval.IOnlineRetrievalMapper;
import org.zipp.ai.infrastructure.dao.retrieval.po.OnlineCandidatePO;
import org.zipp.ai.infrastructure.dao.retrieval.po.OnlineSourcePO;

import java.util.*;

/** Owner-fenced MySQL adapter for source policy, FULLTEXT and final candidate authorization. */
@Repository
public class MySqlOnlineRetrievalAdapter implements EvidenceCatalog, RetrievalLexicalIndex {
    private final IOnlineRetrievalMapper mapper;
    private final MySqlConversationScopeKeyResolver conversationScopes;

    public MySqlOnlineRetrievalAdapter(IOnlineRetrievalMapper mapper) {
        this(mapper, null);
    }

    @Autowired
    public MySqlOnlineRetrievalAdapter(IOnlineRetrievalMapper mapper,
                                       MySqlConversationScopeKeyResolver conversationScopes) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.conversationScopes = conversationScopes;
    }

    @Override
    public SourceResolution resolveSources(EvidencePreparationCommand command) {
        List<String> conversationKeys = conversationKeys(command.owner().ownerKey(),
                command.conversationId(), command.diagramId());
        List<OnlineSourcePO> rows;
        if (!command.selectedVersionIds().isEmpty()) {
            rows = selectExplicitSources(command, conversationKeys);
            if (command.sourceMode() == SourceMode.EXPLICIT) {
                // Selected sources stay first, while EXPLICIT permits one bounded automatic
                // supplement pass. EXPLICIT_ONLY never expands the caller's source set.
                List<OnlineSourcePO> automatic = selectAutomaticSources(command, conversationKeys, 80);
                LinkedHashMap<String, OnlineSourcePO> merged = new LinkedHashMap<>();
                rows.forEach(row -> merged.put(row.getVersionId(), row));
                automatic.forEach(row -> merged.putIfAbsent(row.getVersionId(), row));
                rows = List.copyOf(merged.values());
            }
        } else if (command.sourceMode() == SourceMode.NONE) {
            rows = List.of();
        } else {
            rows = selectAutomaticSources(command, conversationKeys, 80);
        }
        Set<String> found = new HashSet<>();
        Set<String> explicitlySelected = Set.copyOf(command.selectedVersionIds());
        List<AuthorizedSource> sources = rows.stream().map(row -> {
            found.add(row.getVersionId());
            return source(row, explicitlySelected.contains(row.getVersionId()));
        }).toList();
        List<String> missing = command.selectedVersionIds().stream().filter(id -> !found.contains(id)).toList();
        int pending = conversationKeys.stream()
                .mapToInt(key -> value(mapper.countPendingConversationUploads(command.owner().ownerKey(), key)))
                .sum();
        return new SourceResolution(command.sourceMode(), sources, missing, pending);
    }

    private List<OnlineSourcePO> selectExplicitSources(EvidencePreparationCommand command,
                                                       List<String> conversationKeys) {
        LinkedHashMap<String, OnlineSourcePO> merged = new LinkedHashMap<>();
        for (String key : conversationKeys) {
            mapper.selectExplicitSources(command.owner().ownerType().name(), command.owner().ownerKey(),
                            command.diagramId(), key, command.selectedVersionIds())
                    .forEach(row -> merged.putIfAbsent(row.getVersionId(), row));
        }
        return List.copyOf(merged.values());
    }

    private List<OnlineSourcePO> selectAutomaticSources(EvidencePreparationCommand command,
                                                        List<String> conversationKeys, int limit) {
        LinkedHashMap<String, OnlineSourcePO> merged = new LinkedHashMap<>();
        for (String key : conversationKeys) {
            mapper.selectAutomaticSources(command.owner().ownerType().name(), command.owner().ownerKey(),
                            command.diagramId(), key, limit)
                    .forEach(row -> merged.putIfAbsent(row.getVersionId(), row));
        }
        return merged.values().stream().limit(limit).toList();
    }

    private List<String> conversationKeys(String ownerKey, String conversationId, String diagramId) {
        if (conversationScopes == null) {
            return List.of(conversationId);
        }
        return conversationScopes.readableScopeKeys(
                new AuthenticatedActor(ownerKey, ownerKey), conversationId, diagramId).allKeys();
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    @Override
    public List<CandidateRef> search(List<String> queries, AuthorizedSourceSet sources,
                                     RetrievalRoute route, int limit) {
        if (queries.isEmpty() || sources.sources().isEmpty()) return List.of();
        if (route == RetrievalRoute.VISUAL_EXACT) {
            // Exact single-image reconstruction is source-scoped, so natural-language overlap
            // must not hide the authorized visual candidate selected by the user.
            return mapper.selectExactVisualCandidates(
                            sources.owner().ownerType().name(), sources.owner().ownerKey(),
                            sources.sources(), limit)
                    .stream().map(this::candidate).toList();
        }
        String query = String.join(" ", queries);
        boolean includeText = route != RetrievalRoute.VISUAL && route != RetrievalRoute.VISUAL_EXACT;
        boolean includeVisual = route != RetrievalRoute.TEXT;
        return mapper.lexicalSearch(sources.owner().ownerType().name(), sources.owner().ownerKey(),
                        query, sources.sources(), includeText, includeVisual, limit)
                .stream().map(this::candidate).toList();
    }

    @Override
    public List<CandidateRef> resolveVectorCandidates(List<String> vectorIds, AuthorizedSourceSet sources) {
        if (vectorIds.isEmpty() || sources.sources().isEmpty()) return List.of();
        return mapper.resolveVectorCandidates(sources.owner().ownerType().name(), sources.owner().ownerKey(),
                        vectorIds, sources.sources())
                .stream().map(this::candidate).toList();
    }

    @Override
    public List<AuthorizedCandidate> reauthorize(List<String> chunkIds, AuthorizedSourceSet sources, int limit) {
        if (chunkIds.isEmpty() || sources.sources().isEmpty()) return List.of();
        return mapper.reauthorizeCandidates(sources.owner().ownerType().name(), sources.owner().ownerKey(),
                        chunkIds, sources.sources(), limit)
                .stream().map(this::authorized).toList();
    }

    @Override
    public List<CandidateRef> existingTargetCandidates(String diagramId, Long canvasVersion,
                                                       List<String> cellIds,
                                                       AuthorizedSourceSet sources, int limit) {
        if (diagramId == null || diagramId.isBlank() || canvasVersion == null
                || cellIds == null || cellIds.isEmpty() || sources.sources().isEmpty()) return List.of();
        return mapper.selectExistingTargetCandidates(sources.owner().ownerType().name(),
                        sources.owner().ownerKey(), diagramId, canvasVersion, cellIds,
                        sources.sources(), Math.max(1, Math.min(12, limit)))
                .stream().map(this::candidate).toList();
    }

    private AuthorizedSource source(OnlineSourcePO row, boolean required) {
        return new AuthorizedSource(row.getMaterialId(), row.getVersionId(), row.getRevisionId(),
                MaterialScopeType.valueOf(row.getScopeType()), row.getScopeKey(), row.getState(),
                row.isConversationScoped(), required || row.isConversationScoped(),
                row.isHasText(), row.isHasVisual());
    }

    private CandidateRef candidate(OnlineCandidatePO row) {
        return new CandidateRef(row.getChunkId(), row.getModality(), row.getScore());
    }

    private AuthorizedCandidate authorized(OnlineCandidatePO row) {
        // Visual candidates must carry the exact immutable crop, not their retrieval description.
        StoredArtifact artifact = "VISUAL".equals(row.getModality())
                ? new StoredArtifact(row.getVisualObjectKey(), row.getVisualObjectVersionId(),
                        row.getVisualContentSha256(), row.getVisualByteSize(), row.getVisualContentType())
                : new StoredArtifact(row.getRetrievalTextObjectKey(),
                        row.getRetrievalTextObjectVersionId(), row.getRetrievalTextSha256(),
                        row.getRetrievalTextByteSize(), row.getRetrievalTextContentType());
        return new AuthorizedCandidate(row.getChunkId(), row.getEvidenceId(), row.getMaterialId(),
                row.getVersionId(), row.getRevisionId(), row.getModality(), row.getPageNumber(),
                row.getQualityScore(), artifact, row.getSourceLabel());
    }
}
