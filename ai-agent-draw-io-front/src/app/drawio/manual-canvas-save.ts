export type ManualCanvasSaveInput = {
  userId?: string;
  sessionId?: string | null;
  diagramId?: string;
  canvasVersion?: number;
  canvasXml?: string | null;
};

export type ManualCanvasSaveRequest = {
  userId: string;
  sessionId: string;
  diagramId: string;
  expectedVersion?: number;
  canvasXml: string;
};

export const buildManualCanvasSaveRequest = ({
  userId,
  sessionId,
  diagramId,
  canvasVersion,
  canvasXml,
}: ManualCanvasSaveInput): ManualCanvasSaveRequest | null => {
  const normalizedUserId = userId?.trim();
  const normalizedSessionId = sessionId?.trim();
  const normalizedDiagramId = diagramId?.trim();
  const normalizedXml = canvasXml?.trim();
  if (!normalizedUserId || !normalizedSessionId || !normalizedDiagramId || !normalizedXml) return null;

  return {
    userId: normalizedUserId,
    sessionId: normalizedSessionId,
    diagramId: normalizedDiagramId,
    ...(Number.isFinite(canvasVersion) && { expectedVersion: canvasVersion }),
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
