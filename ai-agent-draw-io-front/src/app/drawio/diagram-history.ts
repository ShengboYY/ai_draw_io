export type DiagramHistorySource = {
  diagramId?: string;
  title?: string;
  updatedAt?: string;
};

export type DiagramHistoryEntry = {
  diagramId: string;
  title: string;
  updatedAt?: string;
  updatedAtMs: number;
};

export const diagramHistoryTitle = (diagram: DiagramHistorySource): string => {
  const title = diagram.title?.trim();
  return title || 'Untitled Diagram';
};

export const buildDiagramHistoryEntries = (
  diagrams: DiagramHistorySource[] = [],
): DiagramHistoryEntry[] => {
  const entries: DiagramHistoryEntry[] = [];

  diagrams.forEach(diagram => {
    const diagramId = diagram.diagramId?.trim();
    if (!diagramId) return;

    const updatedAtMs = diagram.updatedAt ? Date.parse(diagram.updatedAt) : 0;
    entries.push({
      diagramId,
      title: diagramHistoryTitle(diagram),
      updatedAt: diagram.updatedAt,
      updatedAtMs: Number.isFinite(updatedAtMs) ? updatedAtMs : 0,
    });
  });

  return entries.sort((a, b) => b.updatedAtMs - a.updatedAtMs);
};
