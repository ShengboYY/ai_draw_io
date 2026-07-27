import test from 'node:test';
import assert from 'node:assert/strict';

import { normalizeRestoredDrawioXml } from '../src/app/drawio/diagram-restore.ts';
import {
  applyOpaqueCellProvenance,
  readOpaqueCellProvenance,
} from '../src/app/drawio/cell-provenance.ts';

test('opaque provenance survives the existing save and reopen normalization path', () => {
  const xml = '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>'
    + '<mxCell id="node-1" value="Agile" vertex="1" parent="1"/>'
    + '</root></mxGraphModel>';

  const withProvenance = applyOpaqueCellProvenance(xml, 'node-1', {
    schema: '1',
    provenanceRef: 'prv_01JOPAQUE9F8X7',
    supportType: 'EVIDENCE',
  });
  const reopened = normalizeRestoredDrawioXml(JSON.stringify({ currentXml: withProvenance }));

  assert.deepEqual(readOpaqueCellProvenance(reopened || '', 'node-1'), {
    schema: '1',
    provenanceRef: 'prv_01JOPAQUE9F8X7',
    supportType: 'EVIDENCE',
  });
  assert.equal(reopened?.includes('Agile Practice Guide'), false);
  assert.equal(reopened?.includes('page='), false);
});

test('opaque provenance rejects non-opaque or XML-breaking values', () => {
  const xml = '<mxGraphModel><root><mxCell id="node-1" vertex="1"/></root></mxGraphModel>';

  assert.throws(() => applyOpaqueCellProvenance(xml, 'node-1', {
    schema: '1',
    provenanceRef: 'Agile Practice Guide page 7',
    supportType: 'EVIDENCE',
  }));
});
