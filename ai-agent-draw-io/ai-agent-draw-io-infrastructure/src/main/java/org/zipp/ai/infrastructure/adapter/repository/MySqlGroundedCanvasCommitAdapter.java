package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.agent.model.valobj.canvas.*;
import org.zipp.ai.domain.grounding.port.GroundedCanvasCommitPort;
import org.zipp.ai.infrastructure.dao.grounding.IGroundedCanvasCommitMapper;
import org.zipp.ai.infrastructure.dao.grounding.GroundedRunRowPO;
import org.zipp.ai.infrastructure.dao.grounding.CellProvenanceTypeRowPO;
import org.zipp.ai.infrastructure.dao.po.CanvasStatePO;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** MySQL implementation of the WP6 linearization point. */
@Repository
public class MySqlGroundedCanvasCommitAdapter implements GroundedCanvasCommitPort {
    private final IGroundedCanvasCommitMapper mapper;

    public MySqlGroundedCanvasCommitAdapter(IGroundedCanvasCommitMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public java.util.Map<String, InheritedProvenance> findPersistedProvenance(InheritanceQuery query) {
        if (query.canvasVersion() == null || query.candidateCellIds().isEmpty()) return java.util.Map.of();
        java.util.LinkedHashMap<String, InheritedProvenance> result = new java.util.LinkedHashMap<>();
        List<CellProvenanceTypeRowPO> cells = mapper.selectPersistedProvenance(query);
        if (cells != null) for (CellProvenanceTypeRowPO cell : cells) {
            result.put(cell.getCellId(), new InheritedProvenance(cell.getProvenanceRef(),
                    org.zipp.ai.domain.citation.model.valobj.SupportType.valueOf(cell.getSupportType())));
        }
        return java.util.Map.copyOf(result);
    }

    @Override
    @Transactional
    public CanvasStateSaveResult commit(CommitPlan plan) {
        GroundedRunRowPO run = mapper.lockRunState(plan);
        if (run == null || !"RUNNING".equals(run.getState())
                || !Objects.equals(plan.expectedRunGeneration(), run.getGeneration())) {
            throw new IllegalStateException("GROUNDED_RUN_NOT_RUNNING");
        }
        int changed = plan.expectedVersion() == null
                ? mapper.insertNewCanvas(plan)
                : mapper.updateExistingCanvas(plan);
        if (changed != 1) {
            throw new CanvasStateVersionConflictException(plan.ownerKey(), plan.diagramId(), plan.expectedVersion());
        }
        CanvasStatePO committed = mapper.selectCommittedCanvas(plan);
        if (committed == null || committed.getVersion() == null) {
            throw new IllegalStateException("COMMITTED_CANVAS_NOT_FOUND");
        }
        long canvasVersion = committed.getVersion();
        requireOne(mapper.insertCanvasVersion(plan, canvasVersion), "CANVAS_VERSION_NOT_INSERTED");
        if (!plan.inheritedCellIds().isEmpty()) {
            mapper.copyInheritedProvenance(plan, Math.max(0, canvasVersion - 1), canvasVersion);
        }
        Set<String> pinned = new HashSet<>();
        for (CitationWrite citation : plan.citations()) {
            requireOne(mapper.insertCitation(plan, citation, canvasVersion), "CITATION_NOT_INSERTED");
            for (EvidenceLink link : citation.evidenceLinks()) {
                requireOne(mapper.insertCitationEvidence(citation.citationId(), link), "CITATION_EVIDENCE_NOT_INSERTED");
                String pinKey = link.materialId() + "|" + link.versionId() + "|" + link.revisionId();
                if (pinned.add(pinKey)) mapper.upsertSourcePin(plan, link);
            }
            requireOne(mapper.upsertCellProvenance(plan, citation, canvasVersion), "PROVENANCE_NOT_WRITTEN");
        }
        mapper.supersedeUnusedPins(plan.diagramId(), canvasVersion);
        requireOne(mapper.completeRun(plan), "GROUNDED_RUN_NOT_COMPLETED");
        CanvasState state = CanvasState.builder()
                .userId(committed.getUserId()).diagramId(committed.getDiagramId())
                .diagramType(committed.getDiagramType()).title(committed.getTitle())
                .thumbnailUrl(committed.getThumbnailUrl()).currentXml(committed.getCurrentXml())
                .contentHash(committed.getContentHash()).summary(committed.getSummary())
                .analysisJson(committed.getAnalysisJson()).version(committed.getVersion())
                .createdAt(committed.getCreatedAt()).updatedAt(committed.getUpdatedAt()).build();
        return plan.expectedVersion() == null
                ? CanvasStateSaveResult.created(state) : CanvasStateSaveResult.updated(state);
    }

    private void requireOne(int changed, String errorCode) {
        if (changed != 1) throw new IllegalStateException(errorCode);
    }
}
