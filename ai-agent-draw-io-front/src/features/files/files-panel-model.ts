import type { MaterialCatalogCard } from '../materials/material-types';

export type FilesPanelGroups = {
  conversationFiles: MaterialCatalogCard[];
  chartbookSharedFiles?: MaterialCatalogCard[];
};

/** Applies the product's CHARTBOOK-over-CONVERSATION display precedence. */
export const buildFilesPanelGroups = ({
  hasChartbook,
  conversationFiles,
  chartbookFiles,
}: {
  hasChartbook: boolean;
  conversationFiles: MaterialCatalogCard[];
  chartbookFiles: MaterialCatalogCard[];
}): FilesPanelGroups => {
  if (!hasChartbook) {
    return { conversationFiles: [...conversationFiles] };
  }
  const sharedIds = new Set(chartbookFiles.map(file => file.materialId));
  return {
    chartbookSharedFiles: [...chartbookFiles],
    conversationFiles: conversationFiles.filter(file => !sharedIds.has(file.materialId)),
  };
};

/** Keeps backend diagnostics out of the user-visible Files panel. */
export const filesPanelStatusLabel = (status: string): string => {
  const normalized = status.trim().toUpperCase();
  if (['IDLE', 'HASHING', 'INITIATING', 'CREATED', 'UPLOADING', 'UPLOADING_BYTES']
    .includes(normalized)) return 'Uploading';
  if (['COMPLETING', 'UPLOADED', 'CHECKING', 'SCANNING'].includes(normalized)) return 'Checking';
  if (normalized === 'PROCESSING' || normalized === 'EXTRACTING' || normalized === 'OCR_VISUAL') {
    return 'Processing';
  }
  if (normalized === 'READY' || normalized === 'SUCCEEDED') return 'Ready';
  if (normalized === 'INDEXING' || normalized === 'PENDING') return 'Indexing';
  if (normalized === 'SEARCHABLE') return 'Searchable';
  if (normalized === 'PARTIAL_READY' || normalized === 'SEARCH_LIMITED') return 'Search limited';
  if (normalized === 'INDEX_FAILED') return 'Failed';
  if (normalized === 'NOT_APPLICABLE') return 'Ready';
  if (normalized.startsWith('REJECTED')) return 'Rejected';
  return 'Failed';
};
