const DEFAULT_DIAGRAM_TITLE = 'Untitled Diagram';
const MAX_DIAGRAM_TITLE_LENGTH = 60;

export const buildDiagramTitleFromPrompt = (prompt?: string | null) => {
  const normalized = (prompt || '').replace(/\s+/g, ' ').trim();
  if (!normalized) return DEFAULT_DIAGRAM_TITLE;
  if (normalized.length <= MAX_DIAGRAM_TITLE_LENGTH) return normalized;
  return normalized.slice(0, MAX_DIAGRAM_TITLE_LENGTH);
};
