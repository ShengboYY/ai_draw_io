export const EMPTY_DRAWIO_XML = '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/></root></mxGraphModel>';

const ROOT_OPEN = '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>';
const ROOT_CLOSE = '</root></mxGraphModel>';
const LEGEND_COLOR_SWATCHES: Record<string, { fill: string; stroke: string }> = {
  blue: { fill: '#2563EB', stroke: '#1D4ED8' },
  green: { fill: '#16A34A', stroke: '#15803D' },
  purple: { fill: '#7C3AED', stroke: '#6D28D9' },
  orange: { fill: '#F97316', stroke: '#EA580C' },
  red: { fill: '#EF4444', stroke: '#DC2626' },
  gray: { fill: '#6B7280', stroke: '#4B5563' },
  grey: { fill: '#6B7280', stroke: '#4B5563' },
};
const LEGEND_COLOR_PREFIX_PATTERN = /(^|(?:&lt;br\s*\/?&gt;|<br\s*\/?>|&gt;|>|\n|\r))(?:\s|&nbsp;)*(blue|green|purple|orange|red|gr[ae]y)\s*:\s*/gi;

const buildLegendSwatchHtml = (colorName: string) => {
  const swatch = LEGEND_COLOR_SWATCHES[colorName.toLowerCase()];
  if (!swatch) return '';

  return `&lt;span style=&quot;display:inline-block;width:10px;height:10px;border-radius:2px;background-color:${swatch.fill};border:1px solid ${swatch.stroke};margin-right:6px;vertical-align:-1px;&quot;&gt;&lt;/span&gt;`;
};

const isLegendLabel = (value: string) => {
  LEGEND_COLOR_PREFIX_PATTERN.lastIndex = 0;
  return /(?:^|(?:&gt;|>))\s*(?:Legend|图例)(?:\s|&lt;br|<br|$)/i.test(value)
    && LEGEND_COLOR_PREFIX_PATTERN.test(value);
};

const replaceLegendColorPrefixes = (value: string) => {
  LEGEND_COLOR_PREFIX_PATTERN.lastIndex = 0;
  return value.replace(LEGEND_COLOR_PREFIX_PATTERN, (_match, linePrefix: string, colorName: string) => {
    return `${linePrefix}${buildLegendSwatchHtml(colorName)}`;
  });
};

const ensureHtmlStyle = (cellTag: string) => {
  const styleMatch = cellTag.match(/\bstyle=(["'])([\s\S]*?)\1/);
  if (!styleMatch) return cellTag.replace(/\s*(\/?>)$/, ' style="html=1;"$1');

  const [styleAttribute, quote, styleValue] = styleMatch;
  if (/(^|;)html=1;?/.test(styleValue)) return cellTag;

  const separator = styleValue && !styleValue.endsWith(';') ? ';' : '';
  return cellTag.replace(styleAttribute, `style=${quote}${styleValue}${separator}html=1;${quote}`);
};

const extractRootCells = (xml?: string | null) => {
  if (!xml) return '';

  const rootStart = xml.indexOf('<root>');
  const rootEnd = xml.lastIndexOf('</root>');
  if (rootStart < 0 || rootEnd < rootStart) return '';

  return xml
    .slice(rootStart + '<root>'.length, rootEnd)
    .replace(/<mxCell\s+id=["']0["']\s*\/>/g, '')
    .replace(/<mxCell\s+id=["']1["']\s+parent=["']0["']\s*\/>/g, '')
    .trim();
};

export const isValidDrawioCellXml = (xml: string | undefined, kind: 'node' | 'edge') => {
  if (!xml || !xml.trim().includes('<mxCell')) return false;
  const wantsVertex = kind === 'node';
  const attributePattern = wantsVertex ? /\bvertex=["']1["']/ : /\bedge=["']1["']/;
  return attributePattern.test(xml);
};

export const normalizeDrawioLegendSwatches = (xml?: string | null) => {
  if (!xml) return '';

  // Convert AI-generated textual legend prefixes like "Blue:" into visual swatches.
  return xml.replace(/<mxCell\b[^>]*>/g, cellTag => {
    const valueMatch = cellTag.match(/\bvalue=(["'])([\s\S]*?)\1/);
    if (!valueMatch) return cellTag;

    const [valueAttribute, quote, value] = valueMatch;
    if (!isLegendLabel(value)) return cellTag;

    const nextValue = replaceLegendColorPrefixes(value);
    if (nextValue === value) return cellTag;

    return ensureHtmlStyle(cellTag.replace(valueAttribute, `value=${quote}${nextValue}${quote}`));
  });
};

export const buildStreamingPreviewXml = (
  nodeCells: string[],
  edgeCells: string[],
  previewSkeleton?: string | null
) => {
  const skeletonCells = extractRootCells(previewSkeleton);
  const cells = [
    skeletonCells,
    ...nodeCells.filter(cell => isValidDrawioCellXml(cell, 'node')),
    ...edgeCells.filter(cell => isValidDrawioCellXml(cell, 'edge')),
  ].filter(Boolean);

  return normalizeDrawioLegendSwatches(`${ROOT_OPEN}${cells.join('')}${ROOT_CLOSE}`);
};
