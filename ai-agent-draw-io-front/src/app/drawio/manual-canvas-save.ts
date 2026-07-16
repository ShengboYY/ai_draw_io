export type ManualCanvasSaveInput = {
  userId?: string;
  sessionId?: string | null;
  diagramId?: string;
  canvasVersion?: number;
  canvasContentHash?: string;
  canvasXml?: string | null;
  visualRepairProvenance?: VisualRepairProvenance;
};

export type VisualRepairProvenance = {
  sourceRunId: string;
  repairRunId: string;
  repairRound: number;
  repairedCanvasXml: string;
};

export type ManualCanvasSaveRequest = {
  userId: string;
  sessionId: string;
  diagramId: string;
  expectedVersion?: number;
  expectedContentHash?: string;
  visualRepairSourceRunId?: string;
  visualRepairRunId?: string;
  visualRepairRound?: number;
  canvasXml: string;
};

export type ManualAutosaveGuardInput = {
  currentSessionId?: string | null;
  editorReady: boolean;
  exportingForChat: boolean;
  exportingThumbnail: boolean;
  hasInlineXml: boolean;
};

export type ConversationDiagramShellInput = {
  diagramId?: string;
  canvasVersion?: number;
  hasDrawableContent: boolean;
  hasConversationMessages: boolean;
};

export const buildManualCanvasSaveRequest = ({
  userId,
  sessionId,
  diagramId,
  canvasVersion,
  canvasContentHash,
  canvasXml,
  visualRepairProvenance,
}: ManualCanvasSaveInput): ManualCanvasSaveRequest | null => {
  const normalizedUserId = userId?.trim();
  const normalizedSessionId = sessionId?.trim();
  const normalizedDiagramId = diagramId?.trim();
  const normalizedXml = canvasXml?.trim();
  if (!normalizedUserId || !normalizedSessionId || !normalizedDiagramId || !normalizedXml) return null;
  const provenanceRound = visualRepairProvenance?.repairRound;
  const provenanceApplies = Boolean(
    visualRepairProvenance?.sourceRunId.trim()
    && visualRepairProvenance?.repairRunId.trim()
    && Number.isFinite(provenanceRound)
    && provenanceRound !== undefined
    && provenanceRound >= 1
    && provenanceRound <= 2
    && visualRepairProvenance?.repairedCanvasXml.trim() !== normalizedXml,
  );

  return {
    userId: normalizedUserId,
    sessionId: normalizedSessionId,
    diagramId: normalizedDiagramId,
    ...(Number.isFinite(canvasVersion) && { expectedVersion: canvasVersion }),
    ...(canvasContentHash?.trim() && { expectedContentHash: canvasContentHash.trim() }),
    ...(provenanceApplies && {
      visualRepairSourceRunId: visualRepairProvenance?.sourceRunId.trim(),
      visualRepairRunId: visualRepairProvenance?.repairRunId.trim(),
      visualRepairRound: visualRepairProvenance?.repairRound,
    }),
    canvasXml: normalizedXml,
  };
};

// Canvas versions grow monotonically on the server, so the highest candidate is always
// the best expectedVersion to lock against, wherever each candidate was learned from.
export const latestCanvasVersion = (
  ...candidates: Array<number | undefined>
): number | undefined => {
  const versions = candidates.filter((value): value is number => Number.isFinite(value));
  return versions.length ? Math.max(...versions) : undefined;
};

export const shouldHandleManualAutosave = ({
  currentSessionId,
  editorReady,
  exportingForChat,
  exportingThumbnail,
  hasInlineXml,
}: ManualAutosaveGuardInput): boolean => {
  if (!currentSessionId || exportingForChat || exportingThumbnail) return false;
  return hasInlineXml || editorReady;
};

export const shouldCreateConversationDiagramShell = ({
  diagramId,
  canvasVersion,
  hasDrawableContent,
  hasConversationMessages,
}: ConversationDiagramShellInput): boolean => (
  Boolean(diagramId?.trim())
  && hasConversationMessages
  && !hasDrawableContent
  && !Number.isFinite(canvasVersion)
);
