type RestorableDiagram = {
  diagramId: string;
  title?: string;
  currentXml?: string;
  version?: number;
};

export const buildRestoredDiagramState = (diagram: RestorableDiagram) => ({
  diagramId: diagram.diagramId,
  title: diagram.title?.trim() || 'Restored Diagram',
  drawIoXml: diagram.currentXml || null,
  canvasVersion: diagram.version,
});
