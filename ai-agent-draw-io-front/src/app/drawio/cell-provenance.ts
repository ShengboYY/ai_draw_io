export type OpaqueCellProvenance = {
  schema: string;
  provenanceRef: string;
  supportType: 'EVIDENCE' | 'AI_KNOWLEDGE' | 'MANUAL' | 'UNATTRIBUTED';
};

const OPAQUE_REF_PATTERN = /^prv_[A-Za-z0-9_-]{8,80}$/;
const SCHEMA_PATTERN = /^[1-9][0-9]{0,3}$/;
const SUPPORT_TYPES = new Set(['EVIDENCE', 'AI_KNOWLEDGE', 'MANUAL', 'UNATTRIBUTED']);

const escapePattern = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const assertProvenance = (value: OpaqueCellProvenance) => {
  if (!SCHEMA_PATTERN.test(value.schema)
    || !OPAQUE_REF_PATTERN.test(value.provenanceRef)
    || !SUPPORT_TYPES.has(value.supportType)) {
    throw new Error('Cell provenance must contain only validated opaque identifiers.');
  }
};

/** Spike implementation for the documented mxCell zipp* attribute format. */
export const applyOpaqueCellProvenance = (
  xml: string,
  cellId: string,
  provenance: OpaqueCellProvenance,
) => {
  assertProvenance(provenance);
  if (!cellId || cellId.length > 128) throw new Error('A valid cell id is required.');
  const cellPattern = new RegExp(
    `<mxCell\\b(?=[^>]*\\bid=(['"])${escapePattern(cellId)}\\1)[^>]*>`,
  );
  let found = false;
  const result = xml.replace(cellPattern, tag => {
    found = true;
    const cleanTag = tag.replace(
      /\s+zipp(?:CitationSchema|ProvenanceRef|SupportType)=(['"])[\s\S]*?\1/g,
      '',
    );
    const suffix = cleanTag.endsWith('/>') ? '/>' : '>';
    const head = cleanTag.slice(0, -suffix.length);
    return `${head} zippCitationSchema="${provenance.schema}"`
      + ` zippProvenanceRef="${provenance.provenanceRef}"`
      + ` zippSupportType="${provenance.supportType}"${suffix}`;
  });
  if (!found) throw new Error('Target cell was not found in draw.io XML.');
  return result;
};

const readAttribute = (tag: string, name: string) => {
  const match = tag.match(new RegExp(`\\b${name}=(['"])([\\s\\S]*?)\\1`));
  return match?.[2] || '';
};

export const readOpaqueCellProvenance = (xml: string, cellId: string): OpaqueCellProvenance | null => {
  const cellPattern = new RegExp(
    `<mxCell\\b(?=[^>]*\\bid=(['"])${escapePattern(cellId)}\\1)[^>]*>`,
  );
  const tag = xml.match(cellPattern)?.[0];
  if (!tag) return null;
  const candidate = {
    schema: readAttribute(tag, 'zippCitationSchema'),
    provenanceRef: readAttribute(tag, 'zippProvenanceRef'),
    supportType: readAttribute(tag, 'zippSupportType'),
  } as OpaqueCellProvenance;
  try {
    assertProvenance(candidate);
    return candidate;
  } catch {
    return null;
  }
};
