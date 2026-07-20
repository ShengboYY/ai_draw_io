package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
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

    public MySqlOnlineRetrievalAdapter(IOnlineRetrievalMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public SourceResolution resolveSources(EvidencePreparationCommand command) {
        List<OnlineSourcePO> rows;
        if (!command.selectedVersionIds().isEmpty()) {
            rows = mapper.selectExplicitSources(command.owner().ownerType().name(), command.owner().ownerKey(),
                    command.diagramId(), command.conversationId(), command.selectedVersionIds());
            if (command.sourceMode() == SourceMode.EXPLICIT) {
                // Selected sources stay first, while EXPLICIT permits one bounded automatic
                // supplement pass. EXPLICIT_ONLY never expands the caller's source set.
                List<OnlineSourcePO> automatic = mapper.selectAutomaticSources(
                        command.owner().ownerType().name(), command.owner().ownerKey(),
                        command.diagramId(), command.conversationId(), 80);
                LinkedHashMap<String, OnlineSourcePO> merged = new LinkedHashMap<>();
                rows.forEach(row -> merged.put(row.getVersionId(), row));
                automatic.forEach(row -> merged.putIfAbsent(row.getVersionId(), row));
                rows = List.copyOf(merged.values());
            }
        } else if (command.sourceMode() == SourceMode.NONE) {
            rows = List.of();
        } else {
            rows = mapper.selectAutomaticSources(command.owner().ownerType().name(), command.owner().ownerKey(),
                    command.diagramId(), command.conversationId(), 80);
        }
        Set<String> found = new HashSet<>();
        Set<String> explicitlySelected = Set.copyOf(command.selectedVersionIds());
        List<AuthorizedSource> sources = rows.stream().map(row -> {
            found.add(row.getVersionId());
            return source(row, explicitlySelected.contains(row.getVersionId()));
        }).toList();
        List<String> missing = command.selectedVersionIds().stream().filter(id -> !found.contains(id)).toList();
        Integer pending = mapper.countPendingConversationUploads(command.owner().ownerKey(), command.conversationId());
        return new SourceResolution(command.sourceMode(), sources, missing, pending == null ? 0 : pending);
    }

    @Override
    public List<CandidateRef> search(List<String> queries, AuthorizedSourceSet sources,
                                     RetrievalRoute route, int limit) {
        if (queries.isEmpty() || sources.sources().isEmpty()) return List.of();
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
        StoredArtifact artifact = new StoredArtifact(row.getRetrievalTextObjectKey(),
                row.getRetrievalTextObjectVersionId(), row.getRetrievalTextSha256(),
                row.getRetrievalTextByteSize(), row.getRetrievalTextContentType());
        return new AuthorizedCandidate(row.getChunkId(), row.getEvidenceId(), row.getMaterialId(),
                row.getVersionId(), row.getRevisionId(), row.getModality(), row.getPageNumber(),
                row.getQualityScore(), artifact, row.getSourceLabel());
    }
}
