export type CanvasStateMetadata = {
  diagramId?: string;
  version?: number;
};

// The backend can replace this later; for now it keeps a browser-created session addressable.
export const makeLocalDiagramId = (sessionId: string) => `diagram-${sessionId}`;

export const mergeCanvasStateMetadata = (
  current: CanvasStateMetadata = {},
  incoming: CanvasStateMetadata = {},
): CanvasStateMetadata => {
  const diagramId = incoming.diagramId?.trim() || current.diagramId;
  const version = Number.isFinite(incoming.version) ? incoming.version : current.version;

  return {
    ...(diagramId && { diagramId }),
    ...(version !== undefined && { version }),
  };
};
