// Never send `spin` here: draw.io's embed protocol treats a non-string `spin`
// value on export requests as a malformed message and answers with a `load`
// event (plus a "Not a diagram file" dialog) instead of the export result.
export const THUMBNAIL_EXPORT_OPTIONS = {
  format: 'png',
  width: '480',
  border: '12',
  background: '#ffffff',
  transparent: false,
} as const;

export const buildThumbnailExportRequest = () => ({
  ...THUMBNAIL_EXPORT_OPTIONS,
});

export const isPngThumbnailDataUrl = (value?: string | null) => (
  typeof value === 'string'
  && value.startsWith('data:image/png;base64,')
  && value.length > 'data:image/png;base64,'.length
);

export const isThumbnailExportResult = ({
  format,
  data,
}: {
  format?: string | null;
  data?: string | null;
}) => format === THUMBNAIL_EXPORT_OPTIONS.format || isPngThumbnailDataUrl(data);

export const shouldPersistThumbnail = ({
  diagramId,
  dataUrl,
}: {
  diagramId?: string | null;
  dataUrl?: string | null;
}) => Boolean(diagramId?.trim()) && isPngThumbnailDataUrl(dataUrl);

export type ThumbnailExportPlan = 'skip' | 'defer' | 'export';

export const planThumbnailExport = ({
  diagramId,
  hasDrawableContent,
  editorReady,
}: {
  diagramId?: string | null;
  hasDrawableContent: boolean;
  editorReady: boolean;
}): ThumbnailExportPlan => {
  if (!diagramId?.trim() || !hasDrawableContent) return 'skip';
  return editorReady ? 'export' : 'defer';
};
