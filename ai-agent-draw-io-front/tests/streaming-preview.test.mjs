import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildStreamingPreviewXml,
  isValidDrawioCellXml,
  normalizeDrawioLegendSwatches,
} from '../src/app/drawio/streaming-preview.ts';

test('buildStreamingPreviewXml wraps streamed node and edge cells into a drawio model', () => {
  const nodeXml = '<mxCell id="n1" value="Node" vertex="1" parent="1"><mxGeometry x="100" y="100" width="120" height="60" as="geometry"/></mxCell>';
  const edgeXml = '<mxCell id="e1" value="calls" edge="1" source="n1" target="n1" parent="1"><mxGeometry relative="1" as="geometry"/></mxCell>';

  const previewXml = buildStreamingPreviewXml([nodeXml], [edgeXml]);

  assert.match(previewXml, /^<mxGraphModel><root>/);
  assert.match(previewXml, /<mxCell id="0"\/><mxCell id="1" parent="0"\/>/);
  assert.match(previewXml, /id="n1"/);
  assert.match(previewXml, /id="e1"/);
  assert.match(previewXml, /<\/root><\/mxGraphModel>$/);
});

test('buildStreamingPreviewXml appends streamed cells to an existing preview skeleton', () => {
  const skeleton = '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/><mxCell id="boundary" value="Boundary" vertex="1" parent="1"/></root></mxGraphModel>';
  const nodeXml = '<mxCell id="n2" value="Child" vertex="1" parent="1"/>';

  const previewXml = buildStreamingPreviewXml([nodeXml], [], skeleton);

  assert.match(previewXml, /id="boundary"/);
  assert.match(previewXml, /id="n2"/);
  assert.equal((previewXml.match(/id="0"/g) || []).length, 1);
});

test('isValidDrawioCellXml rejects empty and wrong-kind cells', () => {
  const nodeXml = '<mxCell id="n1" value="Node" vertex="1" parent="1"/>';
  const edgeXml = '<mxCell id="e1" value="calls" edge="1" parent="1"/>';

  assert.equal(isValidDrawioCellXml(nodeXml, 'node'), true);
  assert.equal(isValidDrawioCellXml(edgeXml, 'edge'), true);
  assert.equal(isValidDrawioCellXml(nodeXml, 'edge'), false);
  assert.equal(isValidDrawioCellXml('', 'node'), false);
});

test('normalizeDrawioLegendSwatches replaces color-name prefixes with swatches', () => {
  const xml = [
    '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>',
    '<mxCell id="legend" value="Legend&lt;br&gt;&lt;font style=&quot;font-size: 11px&quot;&gt;Blue: class lifecycle&lt;br&gt;Green: memory/runtime data&lt;/font&gt;" style="rounded=1;whiteSpace=wrap;html=1;" vertex="1" parent="1"/>',
    '</root></mxGraphModel>',
  ].join('');

  const normalizedXml = normalizeDrawioLegendSwatches(xml);

  assert.doesNotMatch(normalizedXml, /Blue:/);
  assert.doesNotMatch(normalizedXml, /Green:/);
  assert.match(normalizedXml, /background-color:#2563EB/);
  assert.match(normalizedXml, /background-color:#16A34A/);
  assert.match(normalizedXml, /class lifecycle/);
  assert.match(normalizedXml, /memory\/runtime data/);
});
