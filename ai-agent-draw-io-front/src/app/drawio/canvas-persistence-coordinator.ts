type PendingManualCanvasSave = {
  diagramId?: string;
};

export type ManualCanvasConflictRetryInput = {
  queuedCanvasXml?: string | null;
  currentCanvasXml?: string | null;
  aiMutationInFlight: boolean;
};

export type CanvasPersistenceState<TPendingSave extends PendingManualCanvasSave = PendingManualCanvasSave> = {
  aiCanvasMutationDiagramIds: Set<string>;
  pendingManualCanvasSave: TPendingSave | null;
};

export const createCanvasPersistenceState = <TPendingSave extends PendingManualCanvasSave = PendingManualCanvasSave>({
  aiCanvasMutationDiagramIds,
  pendingManualCanvasSave = null,
}: Partial<CanvasPersistenceState<TPendingSave>> = {}): CanvasPersistenceState<TPendingSave> => ({
  aiCanvasMutationDiagramIds: aiCanvasMutationDiagramIds ?? new Set<string>(),
  pendingManualCanvasSave,
});

export const beginAiCanvasMutation = <TPendingSave extends PendingManualCanvasSave>(
  state: CanvasPersistenceState<TPendingSave>,
  diagramId?: string | null,
) => {
  const normalizedDiagramId = diagramId?.trim();
  if (!normalizedDiagramId) {
    return { started: false, droppedPendingManualCanvasSave: false };
  }

  state.aiCanvasMutationDiagramIds.add(normalizedDiagramId);
  const shouldDropPendingSave = state.pendingManualCanvasSave?.diagramId === normalizedDiagramId;
  if (shouldDropPendingSave) {
    // The AI stream will own the next canvas save for this diagram, so the queued save is stale.
    state.pendingManualCanvasSave = null;
  }

  return { started: true, droppedPendingManualCanvasSave: shouldDropPendingSave };
};

export const finishAiCanvasMutation = <TPendingSave extends PendingManualCanvasSave>(
  state: CanvasPersistenceState<TPendingSave>,
  diagramId?: string | null,
) => {
  const normalizedDiagramId = diagramId?.trim();
  if (!normalizedDiagramId) return false;

  return state.aiCanvasMutationDiagramIds.delete(normalizedDiagramId);
};

export const shouldRetryManualCanvasSaveConflict = ({
  queuedCanvasXml,
  currentCanvasXml,
  aiMutationInFlight,
}: ManualCanvasConflictRetryInput): boolean => {
  if (aiMutationInFlight) return false;

  const queued = queuedCanvasXml?.trim();
  const current = currentCanvasXml?.trim();
  // Retrying a stale autosave is only safe while its XML is still the user's current canvas.
  return Boolean(queued && current && queued === current);
};
