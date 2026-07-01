package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.infrastructure.dao.ICanvasStateMapper;
import org.zipp.ai.infrastructure.dao.po.CanvasStatePO;

import javax.annotation.Resource;
import java.util.Optional;

@Repository
public class CanvasStateRepository implements ICanvasStateStore {

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
    @Transactional
    public CanvasState save(CanvasState state) {
        if (state == null) {
            return null;
        }
        CanvasStatePO po = toPo(state);
        canvasStateMapper.upsertDiagram(po);
        canvasStateMapper.upsertCanvasState(po);
        return find(state.getUserId(), state.getDiagramId()).orElse(state);
    }

    private CanvasState toDomain(CanvasStatePO po) {
        return CanvasState.builder()
                .userId(po.getUserId())
                .diagramId(po.getDiagramId())
                .diagramType(po.getDiagramType())
                .currentXml(po.getCurrentXml())
                .summary(po.getSummary())
                .analysisJson(po.getAnalysisJson())
                .version(po.getVersion())
                .build();
    }

    private CanvasStatePO toPo(CanvasState state) {
        CanvasStatePO po = new CanvasStatePO();
        po.setUserId(state.getUserId());
        po.setDiagramId(state.getDiagramId());
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
}
