const XML_ATTRIBUTE_PATTERN = /([:\w.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
const DRAWABLE_CELL_PATTERN = /<mxCell\b([^>]*?)(?:\/\s*>|>([\s\S]*?)<\/mxCell\s*>)/gi;

const decodeXmlEntities = (value: string) => value
  .replace(/&#x([0-9a-f]+);/gi, (_match, codePoint: string) => String.fromCodePoint(Number.parseInt(codePoint, 16)))
  .replace(/&#(\d+);/g, (_match, codePoint: string) => String.fromCodePoint(Number.parseInt(codePoint, 10)))
  .replaceAll('&quot;', '"')
  .replaceAll('&apos;', "'")
  .replaceAll('&lt;', '<')
  .replaceAll('&gt;', '>')
  .replaceAll('&amp;', '&');

const canonicalStyle = (value: string) => value
  .split(';')
  .map(part => part.trim())
  .filter(Boolean)
  .sort()
  .join(';');

const canonicalAttributes = (source: string) => {
  const attributes: Array<[string, string]> = [];
  for (const match of source.matchAll(XML_ATTRIBUTE_PATTERN)) {
    const name = match[1].toLowerCase();
    const decodedValue = decodeXmlEntities(match[2] ?? match[3] ?? '');
    attributes.push([name, name === 'style' ? canonicalStyle(decodedValue) : decodedValue]);
  }
  return attributes
    .sort(([leftName, leftValue], [rightName, rightValue]) => (
      leftName.localeCompare(rightName) || leftValue.localeCompare(rightValue)
    ))
    .map(([name, value]) => `${name}=${JSON.stringify(value)}`)
    .join(',');
};

const attribute = (source: string, name: string) => {
  XML_ATTRIBUTE_PATTERN.lastIndex = 0;
  for (const match of source.matchAll(XML_ATTRIBUTE_PATTERN)) {
    if (match[1].toLowerCase() === name.toLowerCase()) {
      return decodeXmlEntities(match[2] ?? match[3] ?? '');
    }
  }
  return '';
};

const canonicalCellBody = (source: string) => source
  .replace(/<([:\w.-]+)\b([^>]*)>/g, (_match, name: string, rawAttributes: string) => {
    const selfClosing = /\/\s*$/.test(rawAttributes);
    const attributes = canonicalAttributes(rawAttributes);
    return `<${name.toLowerCase()}${attributes ? ` ${attributes}` : ''}${selfClosing ? '/' : ''}>`;
  })
  .replace(/<\/\s*([:\w.-]+)\s*>/g, (_match, name: string) => `</${name.toLowerCase()}>`)
  .replace(/>\s+</g, '><')
  .trim();

/**
 * Fingerprints the drawable graph model rather than viewport metadata. Draw.io
 * may reorder XML attributes on load, but a stale preview must not match the
 * final cell labels, styles, topology, or geometry.
 */
export const drawioCanvasLoadFingerprint = (xml?: string | null) => {
  const cells: string[] = [];
  for (const match of (xml || '').matchAll(DRAWABLE_CELL_PATTERN)) {
    const rawAttributes = match[1];
    if (attribute(rawAttributes, 'vertex') !== '1' && attribute(rawAttributes, 'edge') !== '1') continue;
    cells.push(`${canonicalAttributes(rawAttributes)}|${canonicalCellBody(match[2] || '')}`);
  }
  return cells.sort().join('\n');
};

export const isExpectedDrawioCanvasLoaded = (
  expectedFingerprint: string,
  loadedXml?: string | null,
) => Boolean(expectedFingerprint)
  && drawioCanvasLoadFingerprint(loadedXml) === expectedFingerprint;
