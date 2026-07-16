package org.zipp.ai.domain.agent.service.usage;

import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentDiagramTraceSnapshot;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.model.valobj.usage.AdminUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.UsageDimensionSummary;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IAgentUsageTelemetryStore {

    void insertRun(AgentRunTelemetry run);

    void completeRun(String runId, String status, String errorClass, Instant completedAt, long latencyMs);

    void insertStep(AgentRunStepTelemetry step);

    void insertLlmCall(LlmCallTelemetry call);

    void insertToolCall(ToolCallTelemetry call);

    default void insertTraceEvent(AgentTraceEvent event) {
    }

    default void insertDiagramSnapshot(AgentDiagramTraceSnapshot snapshot) {
    }

    /** Atomically grants one numbered visual-repair attempt owned by a source Draw run. */
    default boolean tryClaimVisualRepair(String sourceRunId,
                                         String reviewedRunId,
                                         String userId,
                                         String diagramId,
                                         String requestId,
                                         Long reviewedVersion,
                                         String reviewedCanvasHash,
                                         int repairRound,
                                         String repairRunId,
                                         Instant occurredAt) {
        return false;
    }

    /** Verifies that a numbered repair claim persisted the exact canvas submitted for follow-up review. */
    default boolean isVisualRepairResult(String sourceRunId,
                                         String repairRunId,
                                         String userId,
                                         String diagramId,
                                         Long repairedVersion,
                                         String repairedCanvasHash,
                                         int repairRound) {
        return false;
    }

    /** Backfills the thumbnail on snapshots whose canvas matches, once the client exports and persists it. */
    default int backfillDiagramSnapshotThumbnail(String diagramId, String canvasHash, String thumbnailUrl) {
        return 0;
    }

    AgentUsageSummary summarizeForUser(String userId);

    AdminUsageSummary summarizeGlobal();

    List<UsageDimensionSummary> summarizeByProviderModelCredentialSource();

    Optional<AgentRunDetail> findRunDetail(String runId);

    default List<AgentDiagramTraceSnapshot> listDiagramSnapshots(String runId) {
        return List.of();
    }

    default List<AgentRunTelemetry> listRuns(String status, String userId, String agentId, int limit, int offset) {
        return List.of();
    }

    /** Returns runs proven terminal by a completion timestamp at or before the immutable sampling snapshot. */
    default List<AgentRunTelemetry> listTerminalRunsAtOrBefore(Instant snapshot, int limit) {
        return listRuns(null, null, null, limit, 0).stream()
                .filter(run -> run != null && !"RUNNING".equalsIgnoreCase(run.getStatus()))
                // Runs without a completion timestamp were not provably terminal at the snapshot and are excluded.
                .filter(run -> run.getCompletedAt() != null && !run.getCompletedAt().isAfter(snapshot))
                .toList();
    }

    /** Returns terminal runs in a completion-time window, newest first. */
    List<AgentRunTelemetry> listTerminalRunsBetween(Instant completedFrom, Instant completedTo, int limit);

    default int anonymizeUser(String userId, String anonymizedUserId) {
        return 0;
    }

    default int deleteTelemetryBefore(Instant cutoff) {
        return 0;
    }
}
