type RestorableDiagram = {
  diagramId: string;
  title?: string;
  currentXml?: unknown;
  version?: number;
  contentHash?: string;
};

const DRAWIO_XML_KEYS = ['xml', 'currentXml', 'drawIoXml', 'content', 'data'];

const extractTaggedDrawioXml = (value: string, tagName: 'mxGraphModel' | 'mxfile') => {
  const openTag = `<${tagName}`;
  const closeTag = `</${tagName}>`;
  const start = value.indexOf(openTag);
  if (start < 0) return null;

  const end = value.lastIndexOf(closeTag);
  if (end >= start) {
    return value.slice(start, end + closeTag.length);
  }

  const tail = value.slice(start).trim();
  if (start === 0 && tail.startsWith(openTag)) return tail;
  if (tail.startsWith(openTag) && tail.endsWith('/>')) return tail;
  return null;
};

export const normalizeRestoredDrawioXml = (value: unknown): string | null => {
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed) return null;

    // Older saved records may wrap the XML in a JSON/export payload.
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
      try {
        const parsed = JSON.parse(trimmed);
        const parsedXml = normalizeRestoredDrawioXml(parsed);
        if (parsedXml) return parsedXml;
      } catch {
        // Fall through and try to find an embedded draw.io XML tag below.
      }
    }

    return extractTaggedDrawioXml(trimmed, 'mxGraphModel')
      || extractTaggedDrawioXml(trimmed, 'mxfile');
  }

  if (Array.isArray(value)) {
    for (const item of value) {
      const itemXml = normalizeRestoredDrawioXml(item);
      if (itemXml) return itemXml;
    }
    return null;
  }

  if (value && typeof value === 'object') {
    const payload = value as Record<string, unknown>;
    for (const key of DRAWIO_XML_KEYS) {
      const xml = normalizeRestoredDrawioXml(payload[key]);
      if (xml) return xml;
    }
  }

  return null;
};

export const buildRestoredDiagramState = (diagram: RestorableDiagram) => ({
  diagramId: diagram.diagramId,
  title: diagram.title?.trim() || 'Restored Diagram',
  drawIoXml: normalizeRestoredDrawioXml(diagram.currentXml),
  canvasVersion: diagram.version,
  ...(diagram.contentHash?.trim() && { canvasContentHash: diagram.contentHash.trim() }),
});
