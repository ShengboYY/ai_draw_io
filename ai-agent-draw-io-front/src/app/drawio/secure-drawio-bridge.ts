export const DRAWIO_BRIDGE_PROTOCOL = 'zipp-drawio-v1';

export type DrawioSelection = {
  cellIds: string[];
  canvasVersion: number;
  contentHash: string;
};

type ParseSelectionInput = {
  eventOrigin: string;
  expectedOrigin: string;
  eventSource: unknown;
  expectedSource: unknown;
  data: unknown;
};

const MAX_SELECTION_SIZE = 200;
const CELL_ID_PATTERN = /^[^\u0000-\u001f]{1,128}$/;
const CONTENT_HASH_PATTERN = /^sha256:[A-Za-z0-9_-]{6,128}$/;

const normalizedCellIds = (values: unknown) => {
  if (!Array.isArray(values) || values.length > MAX_SELECTION_SIZE) return null;
  const cellIds = [...new Set(values.filter(
    (value): value is string => typeof value === 'string' && CELL_ID_PATTERN.test(value),
  ))];
  return cellIds.length === values.length || cellIds.length > 0 ? cellIds : null;
};

export const parseSelectionEvent = ({
  eventOrigin,
  expectedOrigin,
  eventSource,
  expectedSource,
  data,
}: ParseSelectionInput): DrawioSelection | null => {
  // Both checks are required: origin-only validation can accept a sibling iframe on that origin.
  if (eventOrigin !== expectedOrigin || eventSource !== expectedSource) return null;
  try {
    const payload = typeof data === 'string' ? JSON.parse(data) : data;
    if (!payload || typeof payload !== 'object') return null;
    const message = payload as Record<string, unknown>;
    const cellIds = normalizedCellIds(message.cellIds);
    if (message.protocol !== DRAWIO_BRIDGE_PROTOCOL
      || message.event !== 'zippSelection'
      || !cellIds
      || !Number.isSafeInteger(message.canvasVersion)
      || Number(message.canvasVersion) < 0
      || typeof message.contentHash !== 'string'
      || !CONTENT_HASH_PATTERN.test(message.contentHash)) {
      return null;
    }
    return {
      cellIds,
      canvasVersion: Number(message.canvasVersion),
      contentHash: message.contentHash,
    };
  } catch {
    return null;
  }
};

export const createHighlightAction = (
  cellIds: string[],
  canvasVersion: number,
  contentHash: string,
) => {
  const normalized = normalizedCellIds(cellIds);
  if (!normalized || !Number.isSafeInteger(canvasVersion) || canvasVersion < 0
    || !CONTENT_HASH_PATTERN.test(contentHash)) {
    throw new Error('Highlight request requires valid cells and a canvas freshness tuple.');
  }
  return {
    protocol: DRAWIO_BRIDGE_PROTOCOL,
    action: 'zippHighlight' as const,
    cellIds: normalized,
    canvasVersion,
    contentHash,
  };
};

export const createCanvasContextAction = (canvasVersion: number, contentHash: string) => ({
  protocol: DRAWIO_BRIDGE_PROTOCOL,
  action: 'zippCanvasContext' as const,
  canvasVersion,
  contentHash,
});
