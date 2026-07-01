package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateVersionConflictException;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.infrastructure.dao.ICanvasStateMapper;
import org.zipp.ai.infrastructure.dao.po.CanvasStatePO;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class CanvasStateRepository implements ICanvasStateStore {

    private static final String DEFAULT_DIAGRAM_TITLE = "Untitled Diagram";
    private static final int MAX_DIAGRAM_TITLE_LENGTH = 120;

    @Resource
    private ICanvasStateMapper canvasStateMapper;

    @Override
    public Optional<CanvasState> find(String userId, String diagramId) {
        if (isBlank(userId) || isBlank(diagramId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(canvasStateMapper.selectByUserAndDiagram(userId, diagramId))
                .map(this::toDomain);
    }

    @Override
    public List<CanvasState> list(String userId) {
        if (isBlank(userId)) {
            return Collections.emptyList();
        }
        return canvasStateMapper.selectDiagramsByUser(userId).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<CanvasState> rename(String userId, String diagramId, String title) {
        if (isBlank(userId) || isBlank(diagramId)) {
            return Optional.empty();
        }
        int updated = canvasStateMapper.updateDiagramTitle(userId, diagramId, normalizeTitle(title));
        if (updated == 0) {
            return Optional.empty();
        }
        return find(userId, diagramId);
    }

    @Override
    public boolean softDelete(String userId, String diagramId) {
        if (isBlank(userId) || isBlank(diagramId)) {
            return false;
        }
        return canvasStateMapper.softDeleteDiagram(userId, diagramId) > 0;
    }

    @Override
    @Transactional
    public CanvasState save(CanvasState state) {
        if (state == null) {
            return null;
        }
        CanvasStatePO po = toPo(state);
        if (state.getVersion() != null) {
            int updated = canvasStateMapper.updateCanvasStateByVersion(po);
            if (updated == 0) {
                throw new CanvasStateVersionConflictException(state.getUserId(), state.getDiagramId(), state.getVersion());
            }
        } else {
            canvasStateMapper.upsertDiagram(po);
            canvasStateMapper.upsertCanvasState(po);
        }
        CanvasState saved = find(state.getUserId(), state.getDiagramId()).orElse(state);
        canvasStateMapper.syncDiagramVersion(saved.getUserId(), saved.getDiagramId(), saved.getVersion());
        return saved;
    }

    private CanvasState toDomain(CanvasStatePO po) {
        return CanvasState.builder()
                .userId(po.getUserId())
                .diagramId(po.getDiagramId())
                .title(po.getTitle())
                .diagramType(po.getDiagramType())
                .currentXml(po.getCurrentXml())
                .summary(po.getSummary())
                .analysisJson(po.getAnalysisJson())
                .version(po.getVersion())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private CanvasStatePO toPo(CanvasState state) {
        CanvasStatePO po = new CanvasStatePO();
        po.setUserId(state.getUserId());
        po.setDiagramId(state.getDiagramId());
        po.setTitle(state.getTitle());
        po.setDiagramType(state.getDiagramType());
        po.setCurrentXml(state.getCurrentXml());
        po.setSummary(state.getSummary());
        po.setAnalysisJson(state.getAnalysisJson());
        po.setVersion(state.getVersion());
        return po;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeTitle(String title) {
        String normalized = isBlank(title) ? DEFAULT_DIAGRAM_TITLE : title.trim();
        if (normalized.length() <= MAX_DIAGRAM_TITLE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_DIAGRAM_TITLE_LENGTH);
    }
}
