import type { Chartbook } from '../materials/material-types';

// Folder subtitle copy lives here so the shelf and the chartbooks tab stay worded the same.
export const chartbookFolderSummary = (chartbook: Chartbook) => {
  const diagrams = chartbook.diagramIds.length;
  const items = chartbook.materialIds.length;
  return `${diagrams} ${diagrams === 1 ? 'diagram' : 'diagrams'} · ${items} shared ${items === 1 ? 'item' : 'items'}`;
};

const updatedAtMillis = (chartbook: Chartbook) => {
  if (!chartbook.updatedAt) return 0;
  const millis = new Date(chartbook.updatedAt).getTime();
  return Number.isNaN(millis) ? 0 : millis;
};

// Folders follow the diagram grid pipeline: name search first, then newest-first.
export const applyChartbookShelfView = (chartbooks: Chartbook[], query = ''): Chartbook[] => {
  const needle = query.trim().toLowerCase();
  const filtered = needle
    ? chartbooks.filter(chartbook => chartbook.name.toLowerCase().includes(needle))
    : [...chartbooks];
  return filtered.sort((a, b) => updatedAtMillis(b) - updatedAtMillis(a));
};
