export type CanvasStateMetadata = {
  diagramId?: string;
  version?: number;
  contentHash?: string;
};

// The backend can replace this later; for now it keeps a browser-created session addressable.
export const makeLocalDiagramId = (sessionId: string) => `diagram-${sessionId}`;

export const mergeCanvasStateMetadata = (
  current: CanvasStateMetadata = {},
  incoming: CanvasStateMetadata = {},
): CanvasStateMetadata => {
  const diagramId = incoming.diagramId?.trim() || current.diagramId;
  const version = Number.isFinite(incoming.version) ? incoming.version : current.version;
  const contentHash = incoming.contentHash?.trim() || current.contentHash;

  return {
    ...(diagramId && { diagramId }),
    ...(version !== undefined && { version }),
    ...(contentHash && { contentHash }),
  };
};
