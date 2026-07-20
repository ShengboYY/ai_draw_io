package org.zipp.ai.domain.agent.service.canvas;

import org.apache.commons.lang3.StringUtils;
import org.dom4j.Attribute;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisRequest;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasField;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationAuthorization;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationCommand;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationDecision;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationPurpose;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationRejectionReason;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationStatus;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasRepairScope;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateSaveResult;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateVersionConflictException;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.analysis.ICanvasAnalyzer;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only module allowed to turn a final canvas candidate into persisted canvas state.
 * Candidate construction and visual review remain outside this acceptance seam.
 */
@Service
public class CanvasMutationGate {

    private final ICanvasStateStore canvasStateStore;
    private final ICanvasAnalyzer canvasAnalyzer;
    private final DrawioCanvasXmlToolkit xmlToolkit = new DrawioCanvasXmlToolkit();

    public CanvasMutationGate(ICanvasStateStore canvasStateStore, ICanvasAnalyzer canvasAnalyzer) {
        this.canvasStateStore = canvasStateStore;
        this.canvasAnalyzer = canvasAnalyzer;
    }

    public CanvasMutationDecision evaluate(CanvasMutationCommand command) {
        return evaluate(command, true);
    }

    /** Validates a final candidate without persisting it, for a wider atomic commit transaction. */
    public CanvasMutationDecision assess(CanvasMutationCommand command) {
        return evaluate(command, false);
    }

    private CanvasMutationDecision evaluate(CanvasMutationCommand command, boolean persist) {
        Optional<CanvasState> stored = canvasStateStore.find(command.userId(), command.diagramId());
        String beforeXml = stored.map(CanvasState::getCurrentXml)
                .filter(StringUtils::isNotBlank)
                .orElse(StringUtils.defaultString(command.currentXml()));
        CanvasMutationDecision staleDecision = staleDecision(command, stored.orElse(null), beforeXml);
        if (staleDecision != null) {
            return staleDecision;
        }
        // Canonicalization is deliberately structural only: wrap/sanitize XML without guessing
        // geometry, identities, or edge semantics.
        String candidateXml = xmlToolkit.toGraphModel(command.candidateXml());
        DiagramType diagramType = resolveDiagramType(command.diagramType(), stored.orElse(null));
        CanvasAnalysis before = canvasAnalyzer.analyze(new CanvasAnalysisRequest(beforeXml, diagramType, null, null));
        CanvasAnalysis after = canvasAnalyzer.analyze(new CanvasAnalysisRequest(candidateXml, diagramType, null, null));
        Map<String, Set<CanvasField>> changedFields = changedFields(before.getCells(), after.getCells());
        if (!after.isValid() && "critical".equalsIgnoreCase(after.getSeverity())) {
            return new CanvasMutationDecision(
                    CanvasMutationStatus.REJECTED_INVALID_CANDIDATE,
                    beforeXml,
                    before,
                    after,
                    changedFields.keySet(),
                    changedFields,
                    CanvasMutationRejectionReason.INVALID_CANDIDATE,
                    null,
                    stored.orElse(null));
        }
        if (violatesScope(command, changedFields)) {
            return new CanvasMutationDecision(
                    CanvasMutationStatus.REJECTED_SCOPE_VIOLATION,
                    beforeXml,
                    before,
                    after,
                    changedFields.keySet(),
                    changedFields,
                    CanvasMutationRejectionReason.SCOPE_VIOLATION,
                    null,
                    stored.orElse(null));
        }
        if (isRepair(command.purpose())) {
            if (addsIssueOutsideScope(before, after, command.authorization())) {
                return rejectedRepair(
                        CanvasMutationStatus.REJECTED_REGRESSION,
                        CanvasMutationRejectionReason.QUALITY_REGRESSION,
                        beforeXml,
                        before,
                        after,
                        changedFields,
                        stored.orElse(null));
            }
            QualityVector beforeQuality = qualityVector(before);
            QualityVector afterQuality = qualityVector(after);
            if (afterQuality.worseThan(beforeQuality)) {
                return rejectedRepair(
                        CanvasMutationStatus.REJECTED_REGRESSION,
                        CanvasMutationRejectionReason.QUALITY_REGRESSION,
                        beforeXml,
                        before,
                        after,
                        changedFields,
                        stored.orElse(null));
            }
            boolean deterministicImprovement = improvesAnAuthorizedIssue(before, after, command.authorization())
                    && afterQuality.betterThan(beforeQuality);
            boolean boundedVisualChange = command.purpose() == CanvasMutationPurpose.VLM_REPAIR
                    && !changedFields.isEmpty();
            // Pixel-level improvement is verified by the existing post-save VLM pass. At this seam,
            // a VLM candidate must be bounded and non-regressive; deterministic repair must prove improvement.
            if (!deterministicImprovement && !boundedVisualChange) {
                return rejectedRepair(
                        CanvasMutationStatus.NO_SAFE_CANDIDATE,
                        CanvasMutationRejectionReason.NO_SAFE_CANDIDATE,
                        beforeXml,
                        before,
                        after,
                        changedFields,
                        stored.orElse(null));
            }
        }
        if (!persist) {
            return new CanvasMutationDecision(
                    hasIssues(after) ? CanvasMutationStatus.ACCEPTED_WITH_NOTES : CanvasMutationStatus.ACCEPTED,
                    candidateXml, before, after, changedFields.keySet(), changedFields,
                    null, null, stored.orElse(null));
        }
        CanvasStateSaveResult saveResult;
        try {
            saveResult = canvasStateStore.saveWithResult(CanvasState.builder()
                    .userId(command.userId())
                    .diagramId(command.diagramId())
                    .diagramType(diagramType.name().toLowerCase(Locale.ROOT))
                    .currentXml(candidateXml)
                    .version(command.expectedVersion())
                    .build());
        } catch (CanvasStateVersionConflictException conflict) {
            CanvasState latest = canvasStateStore.find(command.userId(), command.diagramId()).orElse(null);
            String latestXml = latest == null || StringUtils.isBlank(latest.getCurrentXml())
                    ? beforeXml
                    : latest.getCurrentXml();
            return rejectedStale(latestXml, CanvasMutationRejectionReason.VERSION_MISMATCH, latest);
        }
        return new CanvasMutationDecision(
                hasIssues(after) ? CanvasMutationStatus.ACCEPTED_WITH_NOTES : CanvasMutationStatus.ACCEPTED,
                candidateXml,
                before,
                after,
                changedFields.keySet(),
                changedFields,
                null,
                saveResult,
                saveResult == null ? null : saveResult.getState());
    }

    private boolean isRepair(CanvasMutationPurpose purpose) {
        return purpose == CanvasMutationPurpose.DETERMINISTIC_REPAIR
                || purpose == CanvasMutationPurpose.VLM_REPAIR;
    }

    private DiagramType resolveDiagramType(DiagramType requested, CanvasState stored) {
        if (requested != null && requested != DiagramType.GENERIC) {
            return requested;
        }
        return stored == null ? DiagramType.GENERIC : DiagramType.from(stored.getDiagramType());
    }

    private boolean hasIssues(CanvasAnalysis analysis) {
        return analysis != null && analysis.getIssues() != null && !analysis.getIssues().isEmpty();
    }

    private CanvasMutationDecision rejectedRepair(CanvasMutationStatus status,
                                                   CanvasMutationRejectionReason reason,
                                                   String beforeXml,
                                                   CanvasAnalysis before,
                                                   CanvasAnalysis after,
                                                   Map<String, Set<CanvasField>> changedFields,
                                                   CanvasState currentState) {
        return new CanvasMutationDecision(
                status,
                beforeXml,
                before,
                after,
                changedFields.keySet(),
                changedFields,
                reason,
                null,
                currentState);
    }

    private boolean improvesAnAuthorizedIssue(CanvasAnalysis before,
                                               CanvasAnalysis after,
                                               CanvasMutationAuthorization authorization) {
        Set<String> allowed = authorization == null ? Set.of() : authorization.allowedCellIds();
        Set<String> remaining = issues(after).stream().map(this::issueKey).collect(Collectors.toSet());
        return issues(before).stream()
                .filter(issue -> intersects(issue.getTargetCellIds(), allowed))
                .map(this::issueKey)
                .anyMatch(key -> !remaining.contains(key));
    }

    private boolean addsIssueOutsideScope(CanvasAnalysis before,
                                           CanvasAnalysis after,
                                           CanvasMutationAuthorization authorization) {
        Set<String> allowed = authorization == null ? Set.of() : authorization.allowedCellIds();
        Set<String> existing = issues(before).stream().map(this::issueKey).collect(Collectors.toSet());
        return issues(after).stream()
                .filter(issue -> !existing.contains(issueKey(issue)))
                .map(CanvasAnalysisIssue::getTargetCellIds)
                .anyMatch(targets -> targets == null || targets.isEmpty() || !allowed.containsAll(targets));
    }

    private List<CanvasAnalysisIssue> issues(CanvasAnalysis analysis) {
        return analysis == null || analysis.getIssues() == null ? List.of() : analysis.getIssues();
    }

    private boolean intersects(List<String> targets, Set<String> allowed) {
        return targets != null && targets.stream().anyMatch(allowed::contains);
    }

    private String issueKey(CanvasAnalysisIssue issue) {
        List<String> targets = issue.getTargetCellIds() == null
                ? List.of()
                : issue.getTargetCellIds().stream().sorted().toList();
        return String.valueOf(issue.getType()) + "|" + String.join(",", targets);
    }

    private QualityVector qualityVector(CanvasAnalysis analysis) {
        int critical = 0;
        int major = 0;
        int minor = 0;
        List<CanvasAnalysisIssue> issues = analysis == null || analysis.getIssues() == null
                ? List.of()
                : analysis.getIssues();
        for (CanvasAnalysisIssue issue : issues) {
            switch (StringUtils.defaultString(issue.getSeverity()).toLowerCase(Locale.ROOT)) {
                case "critical" -> critical++;
                case "major" -> major++;
                case "minor" -> minor++;
                default -> { }
            }
        }
        return new QualityVector(critical, major, minor);
    }

    private boolean violatesScope(CanvasMutationCommand command,
                                  Map<String, Set<CanvasField>> changedFields) {
        if (isRepair(command.purpose())
                && (command.authorization() == null
                || command.authorization().scope() == CanvasRepairScope.WHOLE_CANVAS)) {
            return true;
        }
        if (command.authorization() == null
                || command.authorization().scope() == CanvasRepairScope.WHOLE_CANVAS) {
            return false;
        }
        return changedFields.entrySet().stream().anyMatch(change ->
                !command.authorization().allowedCellIds().contains(change.getKey())
                        || !command.authorization().allowedFields().containsAll(change.getValue()));
    }

    private CanvasMutationDecision staleDecision(CanvasMutationCommand command,
                                                   CanvasState stored,
                                                   String beforeXml) {
        if (stored == null) {
            return null;
        }
        if (command.expectedVersion() == null
                || !Objects.equals(command.expectedVersion(), stored.getVersion())) {
            return rejectedStale(beforeXml, CanvasMutationRejectionReason.VERSION_MISMATCH, stored);
        }
        if (StringUtils.isBlank(command.expectedContentHash())
                || StringUtils.isBlank(stored.getContentHash())
                || !Objects.equals(command.expectedContentHash(), stored.getContentHash())) {
            return rejectedStale(beforeXml, CanvasMutationRejectionReason.CONTENT_HASH_MISMATCH, stored);
        }
        return null;
    }

    private CanvasMutationDecision rejectedStale(String beforeXml,
                                                   CanvasMutationRejectionReason reason,
                                                   CanvasState currentState) {
        return new CanvasMutationDecision(
                CanvasMutationStatus.STALE_VERSION,
                beforeXml,
                null,
                null,
                Set.of(),
                Map.of(),
                reason,
                null,
                currentState);
    }

    private Map<String, Set<CanvasField>> changedFields(List<CanvasCellData> beforeCells,
                                                         List<CanvasCellData> afterCells) {
        Map<String, CanvasCellData> beforeById = byId(beforeCells);
        Map<String, CanvasCellData> afterById = byId(afterCells);
        Set<String> ids = new LinkedHashSet<>(beforeById.keySet());
        ids.addAll(afterById.keySet());
        Map<String, Set<CanvasField>> changes = new LinkedHashMap<>();
        for (String id : ids) {
            CanvasCellData before = beforeById.get(id);
            CanvasCellData after = afterById.get(id);
            Set<CanvasField> fields = fieldsChanged(before, after);
            if (!fields.isEmpty()) {
                changes.put(id, Set.copyOf(fields));
            }
        }
        return Map.copyOf(changes);
    }

    private Map<String, CanvasCellData> byId(List<CanvasCellData> cells) {
        if (cells == null) {
            return Map.of();
        }
        return cells.stream().collect(Collectors.toMap(
                CanvasCellData::getId,
                Function.identity(),
                (first, ignored) -> first,
                LinkedHashMap::new));
    }

    private Set<CanvasField> fieldsChanged(CanvasCellData before, CanvasCellData after) {
        if (before == null) return Set.of(CanvasField.ADD_CELL);
        if (after == null) return Set.of(CanvasField.DELETE_CELL);
        Set<CanvasField> fields = new LinkedHashSet<>();
        if (!Objects.equals(before.getLabel(), after.getLabel())) fields.add(CanvasField.VALUE);
        if (!Objects.equals(before.getStyle(), after.getStyle())) fields.add(CanvasField.STYLE);
        if (!Objects.equals(before.getParentId(), after.getParentId())) fields.add(CanvasField.PARENT);
        if (!Objects.equals(before.getKind(), after.getKind())
                || !Objects.equals(protectedSemantics(before), protectedSemantics(after))) {
            fields.add(CanvasField.SEMANTICS);
        }
        if (!Objects.equals(before.getSource(), after.getSource())
                || !Objects.equals(before.getTarget(), after.getTarget())) {
            fields.add(CanvasField.SOURCE_TARGET);
        }
        if (Double.compare(before.getX(), after.getX()) != 0
                || Double.compare(before.getY(), after.getY()) != 0
                || Double.compare(before.getWidth(), after.getWidth()) != 0
                || Double.compare(before.getHeight(), after.getHeight()) != 0) {
            fields.add(CanvasField.GEOMETRY);
        }
        if (!Objects.equals(before.getSourcePoint(), after.getSourcePoint())
                || !Objects.equals(before.getTargetPoint(), after.getTargetPoint())
                || !Objects.equals(before.getPoints(), after.getPoints())) {
            fields.add(CanvasField.WAYPOINTS);
        }
        return fields;
    }

    private Map<String, String> protectedSemantics(CanvasCellData cell) {
        if (cell == null || StringUtils.isBlank(cell.getRawXml())) {
            return Map.of("kind", StringUtils.defaultString(cell == null ? null : cell.getKind()));
        }
        try {
            Element mxCell = DocumentHelper.parseText(cell.getRawXml()).getRootElement();
            Map<String, String> semantics = new LinkedHashMap<>();
            for (Object value : mxCell.attributes()) {
                Attribute attribute = (Attribute) value;
                String name = attribute.getName();
                if (!Set.of("id", "value", "style", "parent", "source", "target").contains(name)) {
                    semantics.put("cell:" + name, attribute.getValue());
                }
            }
            protectedStyleSemantics(cell.getStyle()).forEach(
                    (name, value) -> semantics.put("style:" + name, value));
            Element geometry = mxCell.element("mxGeometry");
            if (geometry != null) {
                for (Object value : geometry.attributes()) {
                    Attribute attribute = (Attribute) value;
                    String name = attribute.getName();
                    if (!Set.of("x", "y", "width", "height").contains(name)) {
                        semantics.put("geometry:" + name, attribute.getValue());
                    }
                }
            }
            return Map.copyOf(semantics);
        } catch (Exception ignored) {
            // A structurally valid candidate should be parseable; fail closed for scoped repairs if not.
            return Map.of("raw", cell.getRawXml());
        }
    }

    private Map<String, String> protectedStyleSemantics(String style) {
        Map<String, String> semantics = new LinkedHashMap<>();
        for (String token : StringUtils.defaultString(style).split(";")) {
            String normalized = token.trim();
            if (normalized.isEmpty()) continue;
            int separator = normalized.indexOf('=');
            if (separator < 0) {
                // Bare shape tokens such as ellipse, rhombus, swimlane, or actor carry semantics.
                semantics.put("shape-token:" + normalized.toLowerCase(Locale.ROOT), "true");
                continue;
            }
            String key = normalized.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            if (Set.of("shape", "startarrow", "endarrow", "startfill", "endfill", "dashed")
                    .contains(key)) {
                semantics.put(key, normalized.substring(separator + 1).trim());
            }
        }
        return Map.copyOf(semantics);
    }

    private record QualityVector(int critical, int major, int minor) {
        boolean betterThan(QualityVector other) {
            if (critical != other.critical) return critical < other.critical;
            if (major != other.major) return major < other.major;
            return minor < other.minor;
        }

        boolean worseThan(QualityVector other) {
            // Critical and major are independent hard gates, not one lexicographic score.
            if (critical > other.critical || major > other.major) return true;
            if (critical < other.critical || major < other.major) return false;
            return minor > other.minor;
        }
    }
}
