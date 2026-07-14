export const VISUAL_REVIEW_RENDERER_VERSION = 'drawio-embed-png-v1' as const;

export const VISUAL_REVIEW_EXPORT_OPTIONS = {
  format: 'png',
  width: '1600',
  border: '24',
  background: '#ffffff',
  transparent: false,
} as const;

export const buildVisualReviewExportRequest = () => ({
  ...VISUAL_REVIEW_EXPORT_OPTIONS,
});

export const isVisualReviewPngDataUrl = (value?: string | null) => (
  typeof value === 'string'
  && value.startsWith('data:image/png;base64,')
  && value.length > 'data:image/png;base64,'.length
);
