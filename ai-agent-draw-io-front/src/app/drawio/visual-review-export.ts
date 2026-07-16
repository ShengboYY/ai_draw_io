export const VISUAL_REVIEW_RENDERER_VERSION = 'drawio-embed-png-v1' as const;
export const VISUAL_REVIEW_DETAIL_WIDTH = '3200' as const;
export const MAX_VISUAL_REVIEW_PAGES = 4;

export const VISUAL_REVIEW_EXPORT_OPTIONS = {
  format: 'png',
  width: '1600',
  border: '24',
  background: '#ffffff',
  transparent: false,
} as const;

export const buildVisualReviewExportRequest = (
  pageId?: string,
  width: string = VISUAL_REVIEW_EXPORT_OPTIONS.width,
) => ({
  ...VISUAL_REVIEW_EXPORT_OPTIONS,
  width,
  ...(pageId ? { pageId } : {}),
});

export const isVisualReviewPngDataUrl = (value?: string | null) => (
  typeof value === 'string'
  && value.startsWith('data:image/png;base64,')
  && value.length > 'data:image/png;base64,'.length
);

export type VisualReviewPage = {
  pageId?: string;
  pageName: string;
};

export type VisualReviewEvidencePlan = {
  pages: VisualReviewPage[];
  totalPageCount: number;
  truncatedPageCount: number;
  detailTiles: boolean;
};

const decodeXmlAttribute = (value: string) => value
  .replaceAll('&quot;', '"')
  .replaceAll('&apos;', "'")
  .replaceAll('&lt;', '<')
  .replaceAll('&gt;', '>')
  .replaceAll('&amp;', '&');

const attribute = (source: string, name: string) => {
  const match = source.match(new RegExp(`\\b${name}=(?:"([^"]*)"|'([^']*)')`, 'i'));
  return decodeXmlAttribute(match?.[1] ?? match?.[2] ?? '');
};

export const extractDrawioPages = (canvasXml?: string | null): VisualReviewPage[] => {
  const tags = [...(canvasXml || '').matchAll(/<diagram\b([^>]*)>/gi)];
  if (tags.length === 0) return [{ pageName: 'Page-1' }];
  return tags.map((match, index) => ({
    ...(attribute(match[1], 'id') ? { pageId: attribute(match[1], 'id') } : {}),
    pageName: attribute(match[1], 'name') || `Page-${index + 1}`,
  }));
};

const containsSmallText = (canvasXml?: string | null) => (
  [...(canvasXml || '').matchAll(/(?:fontSize=|font-size:\s*)(\d+(?:\.\d+)?)/gi)]
    .some(match => Number(match[1]) < 12)
);

export const buildVisualReviewEvidencePlan = ({
  canvasXml,
  nodeCount,
  edgeCount,
}: {
  canvasXml?: string | null;
  nodeCount: number;
  edgeCount: number;
}): VisualReviewEvidencePlan => {
  const allPages = extractDrawioPages(canvasXml);
  const pages = allPages.slice(0, MAX_VISUAL_REVIEW_PAGES);
  return {
    pages,
    totalPageCount: allPages.length,
    truncatedPageCount: Math.max(0, allPages.length - pages.length),
    // Separate page exports restore per-page legibility; a single page gets tiles when density or font size needs pixels.
    detailTiles: allPages.length === 1
      && (nodeCount >= 16 || edgeCount >= 20 || containsSmallText(canvasXml)),
  };
};

export const createVisualReviewDetailTiles = (dataUrl: string): Promise<string[]> => (
  new Promise((resolve, reject) => {
    if (typeof Image === 'undefined' || typeof document === 'undefined') {
      reject(new Error('Image tiling is unavailable in this runtime.'));
      return;
    }
    const image = new Image();
    image.onload = () => {
      const tiles: string[] = [];
      const columns = 2;
      const rows = 2;
      for (let row = 0; row < rows; row += 1) {
        for (let column = 0; column < columns; column += 1) {
          const sourceX = Math.floor((image.naturalWidth * column) / columns);
          const sourceY = Math.floor((image.naturalHeight * row) / rows);
          const sourceWidth = Math.ceil(image.naturalWidth / columns);
          const sourceHeight = Math.ceil(image.naturalHeight / rows);
          const scale = Math.min(1, 1600 / sourceWidth, 1600 / sourceHeight);
          const canvas = document.createElement('canvas');
          canvas.width = Math.max(1, Math.round(sourceWidth * scale));
          canvas.height = Math.max(1, Math.round(sourceHeight * scale));
          const context = canvas.getContext('2d');
          if (!context) {
            reject(new Error('Could not create a detail-tile canvas.'));
            return;
          }
          context.fillStyle = '#ffffff';
          context.fillRect(0, 0, canvas.width, canvas.height);
          context.drawImage(
            image,
            sourceX,
            sourceY,
            sourceWidth,
            sourceHeight,
            0,
            0,
            canvas.width,
            canvas.height,
          );
          tiles.push(canvas.toDataURL('image/png'));
        }
      }
      resolve(tiles);
    };
    image.onerror = () => reject(new Error('Could not decode the detail image.'));
    image.src = dataUrl;
  })
);
