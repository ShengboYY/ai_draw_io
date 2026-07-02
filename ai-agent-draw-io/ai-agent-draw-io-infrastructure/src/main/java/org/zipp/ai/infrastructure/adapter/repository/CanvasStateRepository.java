package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateVersionConflictException;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.infrastructure.dao.ICanvasStateMapper;
import org.zipp.ai.infrastructure.dao.po.CanvasStatePO;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Repository
public class CanvasStateRepository implements ICanvasStateStore {

    private static final String DEFAULT_DIAGRAM_TITLE = "Untitled Diagram";
    private static final int MAX_DIAGRAM_TITLE_LENGTH = 120;
    private static final int MAX_IMPORT_ID_ATTEMPTS = 10;
    private static final Pattern ANONYMOUS_OWNER_ID = Pattern.compile(
            "^anon_[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    @Resource
    private ICanvasStateMapper canvasStateMapper;
    private Supplier<String> importedDiagramIdSupplier = () -> "diagram_" + UUID.randomUUID();

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
    public int deleteUserData(String userId, String anonymizedUserId) {
        if (isBlank(userId) || isBlank(anonymizedUserId)) {
            return 0;
        }
        int canvasRows = canvasStateMapper.redactCanvasStateForUser(userId, anonymizedUserId);
        int diagramRows = canvasStateMapper.softDeleteAndAnonymizeUserDiagrams(userId, anonymizedUserId);
        return canvasRows + diagramRows;
    }

    @Override
    @Transactional
    public List<CanvasState> importAnonymousWorkspace(String anonymousOwnerId, String targetOwnerId) {
        String sourceOwnerId = normalizeAnonymousOwnerId(anonymousOwnerId);
        String ownerId = trimToNull(targetOwnerId);
        if (sourceOwnerId == null || ownerId == null) {
            return Collections.emptyList();
        }

        List<CanvasStatePO> sources = canvasStateMapper.selectImportableDiagrams(sourceOwnerId);
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }

        List<CanvasState> imported = new ArrayList<>();
        for (CanvasStatePO source : sources) {
            if (source == null || isBlank(source.getDiagramId())) {
                continue;
            }
            String targetDiagramId = nextAvailableImportedDiagramId();
            copyDiagramOrThrow(sourceOwnerId, source.getDiagramId(), ownerId, targetDiagramId);
            canvasStateMapper.insertImportedConversationMessages(sourceOwnerId, source.getDiagramId(), ownerId, targetDiagramId);
            softDeleteSourceOrThrow(sourceOwnerId, source.getDiagramId());
            imported.add(find(ownerId, targetDiagramId)
                    .orElseGet(() -> toImportedDomain(source, ownerId, targetDiagramId)));
        }
        return imported;
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
            // Null-version saves are create-only; existing canvas rows must use optimistic locking.
            if (canvasStateMapper.countCanvasState(state.getUserId(), state.getDiagramId()) > 0) {
                throw new CanvasStateVersionConflictException(state.getUserId(), state.getDiagramId(), null);
            }
            canvasStateMapper.upsertDiagram(po);
            int inserted = canvasStateMapper.insertCanvasState(po);
            if (inserted == 0) {
                throw new CanvasStateVersionConflictException(state.getUserId(), state.getDiagramId(), null);
            }
        }
        return find(state.getUserId(), state.getDiagramId()).orElse(state);
    }

    private void copyDiagramOrThrow(String sourceOwnerId, String sourceDiagramId, String targetOwnerId, String targetDiagramId) {
        int diagramRows = canvasStateMapper.insertImportedDiagram(sourceOwnerId, sourceDiagramId, targetOwnerId, targetDiagramId);
        if (diagramRows == 0) {
            throw new IllegalStateException("Anonymous diagram import failed before canvas copy");
        }
        int canvasRows = canvasStateMapper.insertImportedCanvasState(sourceOwnerId, sourceDiagramId, targetOwnerId, targetDiagramId);
        if (canvasRows == 0) {
            throw new IllegalStateException("Anonymous diagram import failed during canvas copy");
        }
    }

    private void softDeleteSourceOrThrow(String sourceOwnerId, String sourceDiagramId) {
        int deletedRows = canvasStateMapper.softDeleteDiagram(sourceOwnerId, sourceDiagramId);
        if (deletedRows == 0) {
            throw new IllegalStateException("Anonymous diagram import failed during source cleanup");
        }
    }

    private String nextAvailableImportedDiagramId() {
        for (int attempt = 0; attempt < MAX_IMPORT_ID_ATTEMPTS; attempt++) {
            String candidate = trimToNull(importedDiagramIdSupplier.get());
            if (candidate != null && candidate.length() <= 64 && canvasStateMapper.countDiagramById(candidate) == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a safe imported diagram id");
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

    private CanvasState toImportedDomain(CanvasStatePO source, String targetOwnerId, String targetDiagramId) {
        return CanvasState.builder()
                .userId(targetOwnerId)
                .diagramId(targetDiagramId)
                .title(source.getTitle())
                .diagramType(source.getDiagramType())
                .currentXml(source.getCurrentXml())
                .summary(source.getSummary())
                .analysisJson(source.getAnalysisJson())
                .version(source.getVersion())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeAnonymousOwnerId(String ownerId) {
        String normalized = trimToNull(ownerId);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        // Ownership migration is sensitive, so never import from arbitrary caller-supplied ids.
        return ANONYMOUS_OWNER_ID.matcher(normalized).matches() ? normalized : null;
    }

    private String normalizeTitle(String title) {
        String normalized = isBlank(title) ? DEFAULT_DIAGRAM_TITLE : title.trim();
        if (normalized.length() <= MAX_DIAGRAM_TITLE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_DIAGRAM_TITLE_LENGTH);
    }
}
